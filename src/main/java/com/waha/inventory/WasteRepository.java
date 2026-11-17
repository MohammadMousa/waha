package com.waha.inventory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class WasteRepository {

    private static final String NAME_EN = "JSON_UNQUOTE(JSON_EXTRACT(p.name, '$.en'))";
    private static final String NAME_AR = "JSON_UNQUOTE(JSON_EXTRACT(p.name, '$.ar'))";

    private final NamedParameterJdbcTemplate jdbc;

    public WasteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // orgId 0 = every organization (a super admin working without one). storeIds null = every branch.
    public record Filters(long orgId, Set<Long> storeIds, Long branchId, String reason, Long employeeId, Long productId,
                          String search, Timestamp from, Timestamp to) {}

    private static final String FROM = " FROM inventory_wastes w JOIN stores s ON s.id = w.store_id "
        + "LEFT JOIN employees e ON e.id = w.employee_id ";

    private static String where(Filters f, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        p.addValue("pid", f.productId());
        if (f.orgId() > 0) { w.append(" AND w.organization_id = :org"); p.addValue("org", f.orgId()); }
        if (f.storeIds() != null) {
            if (f.storeIds().isEmpty()) w.append(" AND 1=0");
            else { w.append(" AND w.store_id IN (:stores)"); p.addValue("stores", f.storeIds()); }
        }
        if (f.branchId() != null) { w.append(" AND w.store_id = :branch"); p.addValue("branch", f.branchId()); }
        if (f.reason() != null) { w.append(" AND w.reason = :reason"); p.addValue("reason", f.reason()); }
        if (f.employeeId() != null) { w.append(" AND w.employee_id = :emp"); p.addValue("emp", f.employeeId()); }
        if (f.productId() != null)
            w.append(" AND EXISTS (SELECT 1 FROM inventory_waste_items x WHERE x.waste_id = w.id AND x.product_id = :pid)");
        if (f.search() != null) {
            w.append(" AND (w.id = :qid OR w.notes LIKE :q)");
            p.addValue("qid", f.search().matches("\\d{1,18}") ? Long.parseLong(f.search()) : -1L);
            p.addValue("q", "%" + f.search().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.from() != null) { w.append(" AND w.occurred_at >= :from"); p.addValue("from", f.from()); }
        if (f.to() != null) { w.append(" AND w.occurred_at < :to"); p.addValue("to", f.to()); }
        return w.toString();
    }

    public List<Map<String, Object>> list(Filters f, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String w = where(f, p);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(
            "SELECT w.id, w.occurred_at, w.store_id, s.name AS store_name, w.reason, w.employee_id, "
                + "TRIM(CONCAT_WS(' ', e.first_name, e.last_name)) AS employee_name, w.created_by, w.created_by_name, w.notes, "
                + "(SELECT COUNT(*) FROM inventory_waste_items i WHERE i.waste_id = w.id AND (:pid IS NULL OR i.product_id = :pid)) AS item_count, "
                + "(SELECT COALESCE(SUM(i.quantity), 0) FROM inventory_waste_items i WHERE i.waste_id = w.id AND (:pid IS NULL OR i.product_id = :pid)) AS quantity"
                + FROM + w + " ORDER BY w.occurred_at DESC, w.id DESC LIMIT :limit OFFSET :offset", p, (rs, i) -> header(rs, true));
    }

    public long count(Filters f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where(f, p), p, Long.class);
        return n == null ? 0 : n;
    }

    // Total quantity and the quantity and number of wastes per reason, for the same filters as the list.
    public Map<String, Object> summary(Filters f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String w = where(f, p);
        List<Map<String, Object>> byReason = jdbc.query(
            "SELECT w.reason, COUNT(DISTINCT w.id) AS wastes, COALESCE(SUM(i.quantity), 0) AS quantity" + FROM
                + "LEFT JOIN inventory_waste_items i ON i.waste_id = w.id AND (:pid IS NULL OR i.product_id = :pid)"
                + w + " GROUP BY w.reason ORDER BY w.reason", p, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reason", rs.getString("reason"));
                m.put("wastes", rs.getLong("wastes"));
                m.put("quantity", rs.getLong("quantity"));
                return m;
            });
        long total = 0;
        for (Map<String, Object> m : byReason) total += (Long) m.get("quantity");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalQuantity", total);
        out.put("byReason", byReason);
        return out;
    }

    public Optional<Map<String, Object>> find(long id, long orgId, Set<Long> storeIds) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("pid", null);
        String restrict = (orgId > 0 ? " AND w.organization_id = :org" : "")
            + (storeIds != null ? (storeIds.isEmpty() ? " AND 1=0" : " AND w.store_id IN (:stores)") : "");
        if (orgId > 0) p.addValue("org", orgId);
        if (storeIds != null && !storeIds.isEmpty()) p.addValue("stores", storeIds);
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT w.id, w.occurred_at, w.store_id, s.name AS store_name, w.reason, w.employee_id, "
                + "TRIM(CONCAT_WS(' ', e.first_name, e.last_name)) AS employee_name, w.created_by, w.created_by_name, w.notes, w.created_at"
                + FROM + "WHERE w.id = :id" + restrict, p, (rs, i) -> {
                Map<String, Object> m = header(rs, false);
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                return m;
            });
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> out = rows.get(0);
        List<Map<String, Object>> items = jdbc.query(
            "SELECT i.product_id, i.quantity, p.barcode, " + NAME_EN + " AS name_en, " + NAME_AR + " AS name_ar "
                + "FROM inventory_waste_items i JOIN products p ON p.id = i.product_id WHERE i.waste_id = :id ORDER BY i.id",
            new MapSqlParameterSource("id", id), (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", rs.getLong("product_id"));
                m.put("barcode", rs.getString("barcode"));
                m.put("nameEn", rs.getString("name_en"));
                m.put("nameAr", rs.getString("name_ar"));
                m.put("quantity", rs.getInt("quantity"));
                return m;
            });
        out.put("items", items);
        out.put("itemCount", items.size());
        out.put("quantity", items.stream().mapToLong(m -> (Integer) m.get("quantity")).sum());
        return Optional.of(out);
    }

    // One row per waste line, newest first, for the download.
    public List<List<String>> exportRows(Filters f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String w = where(f, p);
        return jdbc.query(
            "SELECT w.id, w.occurred_at, s.name AS store_name, w.reason, TRIM(CONCAT_WS(' ', e.first_name, e.last_name)) AS employee_name, "
                + "w.created_by_name, w.notes, p.barcode, " + NAME_EN + " AS name_en, " + NAME_AR + " AS name_ar, i.quantity"
                + FROM + "JOIN inventory_waste_items i ON i.waste_id = w.id AND (:pid IS NULL OR i.product_id = :pid) "
                + "JOIN products p ON p.id = i.product_id" + w + " ORDER BY w.occurred_at DESC, w.id DESC, i.id LIMIT 100000", p,
            (rs, n) -> Arrays.asList(String.valueOf(rs.getLong("id")), rs.getTimestamp("occurred_at").toInstant().toString(),
                rs.getString("store_name"), rs.getString("reason"), nz(rs.getString("employee_name")), nz(rs.getString("created_by_name")),
                nz(rs.getString("notes")), nz(rs.getString("barcode")), nz(rs.getString("name_en")), nz(rs.getString("name_ar")),
                String.valueOf(rs.getInt("quantity"))));
    }

    private static Map<String, Object> header(java.sql.ResultSet rs, boolean withCounts) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
        m.put("branchId", rs.getLong("store_id"));
        m.put("branchName", rs.getString("store_name"));
        m.put("reason", rs.getString("reason"));
        long emp = rs.getLong("employee_id");
        m.put("employeeId", rs.wasNull() ? null : emp);
        String en = rs.getString("employee_name");
        m.put("employeeName", en == null || en.isBlank() ? null : en);
        long by = rs.getLong("created_by");
        m.put("createdBy", rs.wasNull() ? null : by);
        m.put("createdByName", rs.getString("created_by_name"));
        m.put("notes", rs.getString("notes"));
        if (withCounts) {
            m.put("itemCount", rs.getInt("item_count"));
            m.put("quantity", rs.getLong("quantity"));
        }
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ── checks used before saving ────────────────────────────────────────────

    public record StoreInfo(long organizationId, boolean active) {}

    public Optional<StoreInfo> store(long storeId) {
        return jdbc.query("SELECT organization_id, active FROM stores WHERE id = :id", new MapSqlParameterSource("id", storeId),
            (rs, i) -> new StoreInfo(rs.getLong(1), rs.getBoolean(2))).stream().findFirst();
    }

    public List<Long> storeIds(long orgId) {
        return jdbc.query("SELECT id FROM stores WHERE (:org = 0 OR organization_id = :org)", new MapSqlParameterSource("org", orgId),
            (rs, i) -> rs.getLong(1));
    }

    public boolean employeeInOrg(long employeeId, long orgId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE id = :e AND organization_id = :o",
            new MapSqlParameterSource("e", employeeId).addValue("o", orgId), Integer.class);
        return n != null && n > 0;
    }

    public Set<Long> productsInOrg(Collection<Long> ids, long orgId) {
        return new HashSet<>(jdbc.query("SELECT id FROM products WHERE id IN (:ids) AND company_id = :o",
            new MapSqlParameterSource("ids", ids).addValue("o", orgId), (rs, i) -> rs.getLong(1)));
    }

    public Optional<Long> findByClientRef(long orgId, String clientRef) {
        return jdbc.query("SELECT id FROM inventory_wastes WHERE organization_id = :o AND client_ref = :c",
            new MapSqlParameterSource("o", orgId).addValue("c", clientRef), (rs, i) -> rs.getLong(1)).stream().findFirst();
    }

    // ── saving ───────────────────────────────────────────────────────────────

    public record NewWaste(long orgId, long storeId, String reason, Instant occurredAt, Long employeeId, String notes,
                           String clientRef, Long userId, String userName, Map<Long, Integer> lines) {}

    // Saves the waste and lowers the branch's stock in one transaction. Stock may go negative (same rule as transfers
    // and sales). Returns the new id, or the existing one when the same clientRef was already saved.
    @Transactional
    public long create(NewWaste w) {
        if (w.clientRef() != null) {
            Optional<Long> existing = findByClientRef(w.orgId(), w.clientRef());
            if (existing.isPresent()) return -existing.get();      // negative = already saved
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("o", w.orgId()).addValue("s", w.storeId())
            .addValue("reason", w.reason()).addValue("at", Timestamp.from(w.occurredAt()))
            .addValue("emp", w.employeeId()).addValue("by", w.userId()).addValue("byName", w.userName())
            .addValue("notes", w.notes()).addValue("cref", w.clientRef());
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        try {
            jdbc.update("INSERT INTO inventory_wastes (organization_id, store_id, reason, occurred_at, employee_id, "
                + "created_by, created_by_name, notes, client_ref) VALUES (:o, :s, :reason, :at, :emp, :by, :byName, :notes, :cref)",
                p, keys, new String[]{"id"});
        } catch (DuplicateKeyException e) {
            // two saves of the same clientRef at the same moment: the other one won
            Optional<Long> existing = w.clientRef() == null ? Optional.empty() : findByClientRef(w.orgId(), w.clientRef());
            if (existing.isPresent()) return -existing.get();
            throw e;
        }
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        for (Map.Entry<Long, Integer> line : w.lines().entrySet()) {
            MapSqlParameterSource l = new MapSqlParameterSource("w", id).addValue("p", line.getKey()).addValue("q", line.getValue())
                .addValue("s", w.storeId()).addValue("neg", -line.getValue());
            jdbc.update("INSERT INTO inventory_waste_items (waste_id, product_id, quantity) VALUES (:w, :p, :q)", l);
            jdbc.update("INSERT INTO store_inventory (store_id, product_id, quantity) VALUES (:s, :p, :neg) "
                + "ON DUPLICATE KEY UPDATE quantity = quantity + :neg", l);
        }
        return id;
    }
}
