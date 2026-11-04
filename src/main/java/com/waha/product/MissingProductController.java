package com.waha.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

// Report of barcodes that kiosks scanned but the catalog did not have, with the products created since.
@RestController
@RequestMapping("/api/admin/products/missing")
public class MissingProductController {

    private static final int MAX_REMOVE = 500;
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private final SessionService sessionService;
    private final MissingProductTracker tracker;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MissingProductController(SessionService sessionService, MissingProductTracker tracker,
                                    NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.sessionService = sessionService;
        this.tracker = tracker;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static ResponseEntity<?> orgRequired() {
        return ResponseEntity.status(409).body(Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));
    }

    private static final String FROM_SQL =
        "FROM product_missing m JOIN stores s ON s.id = m.store_id "
        + "LEFT JOIN product_barcodes pb ON pb.barcode = m.barcode "
        + "LEFT JOIN products p ON p.id = pb.product_id AND p.company_id = s.organization_id ";

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestParam(required = false) Long storeId,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String username,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(defaultValue = "open") String status,
                                  @RequestParam(defaultValue = "last") String sort,
                                  @RequestParam(defaultValue = "desc") String dir,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.VIEW_PRODUCTS, session.organizationId());
        if (session.organizationId() <= 0) return orgRequired();
        if (!Set.of("open", "created", "all").contains(status))
            return ResponseEntity.badRequest().body(new ErrorResponse("status must be open, created or all"));
        MapSqlParameterSource p = new MapSqlParameterSource("org", session.organizationId());
        StringBuilder where = new StringBuilder("WHERE s.organization_id = :org ");
        if (storeId != null) { where.append("AND m.store_id = :store "); p.addValue("store", storeId); }
        if (q != null && !q.isBlank()) { where.append("AND m.barcode LIKE :q "); p.addValue("q", "%" + q.trim() + "%"); }
        if (username != null && !username.isBlank()) { where.append("AND m.last_username LIKE :u "); p.addValue("u", "%" + username.trim() + "%"); }
        try {
            if (from != null && !from.isBlank()) {
                where.append("AND m.last_missed_at >= :from ");
                p.addValue("from", Timestamp.from(LocalDate.parse(from).atStartOfDay(RIYADH).toInstant()));
            }
            if (to != null && !to.isBlank()) {
                where.append("AND m.last_missed_at < :to ");
                p.addValue("to", Timestamp.from(LocalDate.parse(to).plusDays(1).atStartOfDay(RIYADH).toInstant()));
            }
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("from and to must be dates like 2026-11-01"));
        }

        tracker.flushNow();

        // Cards: over the same filters, whatever the status filter and page.
        Map<String, Object> cards = jdbc.queryForMap(
            "SELECT COUNT(CASE WHEN p.id IS NULL THEN 1 END) AS missing, "
            + "COALESCE(SUM(CASE WHEN p.id IS NULL THEN m.miss_count END), 0) AS scans, "
            + "COUNT(CASE WHEN p.id IS NULL AND m.last_missed_at >= :week THEN 1 END) AS recent, "
            + "COUNT(CASE WHEN p.id IS NOT NULL THEN 1 END) AS created "
            + FROM_SQL + where, new MapSqlParameterSource(p.getValues())
                .addValue("week", Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(7)))));

        String statusSql = status.equals("open") ? "AND p.id IS NULL " : status.equals("created") ? "AND p.id IS NOT NULL " : "";
        String col = switch (sort) {
            case "count" -> "m.miss_count";
            case "first" -> "m.first_missed_at";
            case "barcode" -> "m.barcode";
            default -> "m.last_missed_at";
        };
        String order = dir.equalsIgnoreCase("asc") ? "ASC" : "DESC";
        int sz = Math.min(Math.max(size, 1), 200), pg = Math.max(page, 0);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM_SQL + where + statusSql, p, Long.class);
        p.addValue("lim", sz).addValue("off", pg * sz);
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT m.id, m.store_id, s.name AS store_name, m.barcode, m.miss_count, m.first_missed_at, m.last_missed_at, "
            + "m.last_username, p.id AS pid, p.name AS pname, p.price AS pprice " + FROM_SQL + where + statusSql
            + "ORDER BY " + col + " " + order + ", m.id DESC LIMIT :lim OFFSET :off", p, (rs, i) -> row(rs));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", Map.of("missingProducts", cards.get("missing"), "scansMissed", cards.get("scans"),
            "missedLast7Days", cards.get("recent"), "created", cards.get("created")));
        out.put("rows", rows);
        out.put("page", pg);
        out.put("size", sz);
        out.put("total", total);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", rs.getLong("id"));
        r.put("storeId", rs.getLong("store_id"));
        r.put("storeName", rs.getString("store_name"));
        r.put("barcode", rs.getString("barcode"));
        r.put("missCount", rs.getLong("miss_count"));
        r.put("firstMissedAt", rs.getTimestamp("first_missed_at").toInstant());
        r.put("lastMissedAt", rs.getTimestamp("last_missed_at").toInstant());
        r.put("lastUsername", rs.getString("last_username"));
        long pid = rs.getLong("pid");
        if (rs.wasNull()) {
            r.put("product", null);
        } else {
            Map<String, Object> prod = new LinkedHashMap<>();
            prod.put("id", pid);
            prod.put("name", parse(rs.getString("pname")));
            prod.put("price", rs.getBigDecimal("pprice"));
            r.put("product", prod);
        }
        return r;
    }

    private JsonNode parse(String raw) {
        try { return raw == null ? null : mapper.readTree(raw); } catch (Exception e) { return null; }
    }

    record RemoveRequest(List<Long> ids) {}

    // Removes the chosen records.
    @PostMapping("/remove")
    public ResponseEntity<?> remove(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody RemoveRequest body) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.EDIT_PRODUCTS, session.organizationId());
        if (session.organizationId() <= 0) return orgRequired();
        if (body == null || body.ids() == null || body.ids().isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("ids is required"));
        if (body.ids().size() > MAX_REMOVE)
            return ResponseEntity.badRequest().body(new ErrorResponse("At most " + MAX_REMOVE + " ids per call"));
        tracker.flushNow();
        List<Long> ids = body.ids().stream().distinct().toList();
        List<Map<String, Object>> found = jdbc.queryForList(
            "SELECT m.id, m.store_id, m.barcode FROM product_missing m JOIN stores s ON s.id = m.store_id "
            + "WHERE s.organization_id = :org AND m.id IN (:ids)",
            new MapSqlParameterSource("org", session.organizationId()).addValue("ids", ids));
        return ResponseEntity.ok(delete(found, ids));
    }

    // Checks every record against the catalog and removes the ones whose product has since been created.
    // Returns what was removed, with the created products.
    @PostMapping("/clean")
    public ResponseEntity<?> clean(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestParam(required = false) Long storeId) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.EDIT_PRODUCTS, session.organizationId());
        if (session.organizationId() <= 0) return orgRequired();
        tracker.flushNow();
        MapSqlParameterSource p = new MapSqlParameterSource("org", session.organizationId());
        String store = "";
        if (storeId != null) { store = "AND m.store_id = :store "; p.addValue("store", storeId); }
        List<Map<String, Object>> created = jdbc.query(
            "SELECT m.id, m.store_id, s.name AS store_name, m.barcode, m.miss_count, m.first_missed_at, m.last_missed_at, "
            + "m.last_username, p.id AS pid, p.name AS pname, p.price AS pprice " + FROM_SQL
            + "WHERE s.organization_id = :org AND p.id IS NOT NULL " + store + "ORDER BY m.id", p, (rs, i) -> row(rs));
        List<Long> ids = created.stream().map(r -> (Long) r.get("id")).toList();
        List<Map<String, Object>> found = created.stream()
            .map(r -> Map.<String, Object>of("id", r.get("id"), "store_id", r.get("storeId"), "barcode", r.get("barcode")))
            .toList();
        Map<String, Object> out = new LinkedHashMap<>(delete(found, ids));
        out.put("items", created);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> delete(List<Map<String, Object>> found, List<Long> asked) {
        Set<Long> foundIds = new HashSet<>();
        for (Map<String, Object> f : found) {
            foundIds.add(((Number) f.get("id")).longValue());
            tracker.discard(((Number) f.get("store_id")).longValue(), (String) f.get("barcode"));
        }
        if (!foundIds.isEmpty()) {
            jdbc.update("DELETE FROM product_missing WHERE id IN (:ids)", Map.of("ids", foundIds));
        }
        List<Long> notFound = asked.stream().filter(i -> !foundIds.contains(i)).toList();
        return Map.of("removed", foundIds.size(), "notFound", notFound);
    }
}
