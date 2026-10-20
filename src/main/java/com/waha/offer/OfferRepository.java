package com.waha.offer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class OfferRepository {

    private static final long CACHE_MILLIS = 60_000;

    private record Cached(long loadedAt, List<Offer> offers) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<Long, Cached> byStore = new ConcurrentHashMap<>();

    public OfferRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ── Pricing side ──────────────────────────────────────────────────────────

    // Enabled, not deleted offers that apply to this store (client and store scope). Dates are checked by
    // the caller on every use, so the cache never holds an offer past its end date. Cached for 60 seconds.
    public List<Offer> loadForStore(long storeId) {
        Cached c = byStore.get(storeId);
        if (c != null && System.currentTimeMillis() - c.loadedAt() < CACHE_MILLIS) return c.offers();
        List<Offer> offers = queryForStore(storeId);
        byStore.put(storeId, new Cached(System.currentTimeMillis(), offers));
        return offers;
    }

    public void clearCache() {
        byStore.clear();
    }

    private List<Offer> queryForStore(long storeId) {
        List<Offer> base = jdbc.query("""
            SELECT o.* FROM offers o
            JOIN stores s ON s.id = ? AND s.organization_id = o.organization_id
            WHERE o.deleted_at IS NULL AND o.enabled = 1
              AND (o.store_scope = 'ALL'
                   OR EXISTS (SELECT 1 FROM offer_stores os WHERE os.offer_id = o.id AND os.store_id = ?))
            """, (rs, i) -> toOffer(rs, Set.of()), storeId, storeId);
        if (base.isEmpty()) return List.of();

        Map<Long, Set<Long>> products = productsOf(base.stream().map(Offer::id).toList());
        return base.stream().map(o -> new Offer(o.id(), o.organizationId(), o.name(), o.type(), o.discountMode(),
            o.discountValue(), o.maxDiscountAmount(), o.startDate(), o.endDate(), o.enabled(), o.storeScope(),
            products.getOrDefault(o.id(), Set.of()))).toList();
    }

    private Map<Long, Set<Long>> productsOf(List<Long> offerIds) {
        String in = offerIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, Set<Long>> out = new HashMap<>();
        jdbc.query("SELECT offer_id, product_id FROM offer_products WHERE offer_id IN (" + in + ")",
            rs -> { out.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getLong(2)); },
            offerIds.toArray());
        return out;
    }

    private static Offer toOffer(java.sql.ResultSet rs, Set<Long> products) throws java.sql.SQLException {
        BigDecimal max = rs.getBigDecimal("max_discount_amount");
        return new Offer(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"),
            rs.getString("type"), rs.getString("discount_mode"), rs.getBigDecimal("discount_value"), max,
            rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
            rs.getBoolean("enabled"), rs.getString("store_scope"), products);
    }

    // ── Admin side ────────────────────────────────────────────────────────────

    public record Page(List<Map<String, Object>> items, long totalCount) {}

    private static final String STATUS_SQL = """
        CASE WHEN o.enabled = 0 THEN 'DISABLED'
             WHEN o.end_date < ? THEN 'EXPIRED'
             WHEN o.start_date > ? THEN 'SCHEDULED'
             ELSE 'ENABLED' END""";

    public Page list(long orgId, String status, String q, LocalDate today, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE o.organization_id = ? AND o.deleted_at IS NULL");
        List<Object> where_args = new ArrayList<>(List.of(orgId));
        if (q != null && !q.isBlank()) {
            where.append(" AND (o.name LIKE ? OR CAST(o.id AS CHAR) = ?)");
            where_args.add("%" + q.trim() + "%");
            where_args.add(q.trim());
        }
        String having = "";
        List<Object> having_args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            having = " AND (" + STATUS_SQL + ") = ?";
            having_args.addAll(List.of(Date.valueOf(today), Date.valueOf(today), status.toUpperCase()));
        }
        List<Object> countArgs = new ArrayList<>(where_args);
        countArgs.addAll(having_args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM offers o" + where + having, Long.class, countArgs.toArray());

        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(today));
        args.add(Date.valueOf(today));
        args.addAll(where_args);
        args.addAll(having_args);
        args.add(size);
        args.add((long) page * size);
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT o.*, " + STATUS_SQL.replace("?", "?") + " AS status FROM offers o" + where + having
                + " ORDER BY o.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> row(rs), args.toArray());
        return new Page(rows, total == null ? 0 : total);
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("clientId", rs.getLong("organization_id"));
        m.put("name", rs.getString("name"));
        m.put("type", rs.getString("type"));
        m.put("discountMode", rs.getString("discount_mode"));
        m.put("discountValue", rs.getBigDecimal("discount_value"));
        m.put("maxDiscountAmount", rs.getBigDecimal("max_discount_amount"));
        m.put("startDate", rs.getDate("start_date").toLocalDate().toString());
        m.put("endDate", rs.getDate("end_date").toLocalDate().toString());
        m.put("enabled", rs.getBoolean("enabled"));
        m.put("storeScope", rs.getString("store_scope"));
        try { m.put("status", rs.getString("status")); } catch (java.sql.SQLException ignored) { }
        m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        return m;
    }

    // One offer (not deleted) with its stores and products; empty if it does not exist.
    public Optional<Map<String, Object>> find(long id, LocalDate today) {
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT o.*, " + STATUS_SQL + " AS status FROM offers o WHERE o.id = ? AND o.deleted_at IS NULL",
            (rs, i) -> row(rs), Date.valueOf(today), Date.valueOf(today), id);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> m = rows.get(0);
        m.put("stores", jdbc.query("SELECT s.id, s.name FROM offer_stores os JOIN stores s ON s.id = os.store_id " +
            "WHERE os.offer_id = ? ORDER BY s.name",
            (rs, i) -> Map.<String, Object>of("id", rs.getLong(1), "name", rs.getString(2)), id));
        m.put("products", jdbc.query("SELECT p.id, p.barcode, p.name FROM offer_products op JOIN products p ON p.id = op.product_id " +
            "WHERE op.offer_id = ? ORDER BY p.id",
            (rs, i) -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", rs.getLong(1));
                p.put("barcode", rs.getString(2));
                p.put("name", json(rs.getString(3)));
                return p;
            }, id));
        m.put("recentApplications", jdbc.query(
            "SELECT order_id, discount_amount, created_at FROM offer_applications WHERE offer_id = ? ORDER BY id DESC LIMIT 10",
            (rs, i) -> Map.<String, Object>of("orderId", rs.getString(1), "discountAmount", rs.getBigDecimal(2),
                "createdAt", rs.getTimestamp(3).toInstant().toString()), id));
        BigDecimal total = jdbc.queryForObject("SELECT COALESCE(SUM(discount_amount), 0) FROM offer_applications WHERE offer_id = ?",
            BigDecimal.class, id);
        m.put("totalDiscountGiven", total);
        return Optional.of(m);
    }

    private JsonNode json(String raw) {
        try { return raw == null ? null : mapper.readTree(raw); } catch (Exception e) { return null; }
    }

    public record Fields(long organizationId, String name, String type, String discountMode, BigDecimal discountValue,
                         BigDecimal maxDiscountAmount, LocalDate startDate, LocalDate endDate, boolean enabled,
                         String storeScope, List<Long> storeIds, List<Long> productIds) {}

    @Transactional
    public long insert(Fields f, Long userId) {
        jdbc.update("""
            INSERT INTO offers (organization_id, name, type, discount_mode, discount_value, max_discount_amount,
                                start_date, end_date, enabled, store_scope, created_by, updated_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """, f.organizationId(), f.name(), f.type(), f.discountMode(), f.discountValue(), f.maxDiscountAmount(),
            Date.valueOf(f.startDate()), Date.valueOf(f.endDate()), f.enabled(), f.storeScope(), userId, userId);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        replaceTargets(id, f);
        clearCache();
        return id;
    }

    // The type never changes after creation.
    @Transactional
    public void update(long id, Fields f, Long userId) {
        jdbc.update("""
            UPDATE offers SET name = ?, discount_mode = ?, discount_value = ?, max_discount_amount = ?,
                   start_date = ?, end_date = ?, enabled = ?, store_scope = ?, updated_by = ?
            WHERE id = ? AND deleted_at IS NULL
            """, f.name(), f.discountMode(), f.discountValue(), f.maxDiscountAmount(),
            Date.valueOf(f.startDate()), Date.valueOf(f.endDate()), f.enabled(), f.storeScope(), userId, id);
        replaceTargets(id, f);
        clearCache();
    }

    private void replaceTargets(long id, Fields f) {
        jdbc.update("DELETE FROM offer_stores WHERE offer_id = ?", id);
        jdbc.update("DELETE FROM offer_products WHERE offer_id = ?", id);
        if ("SOME".equals(f.storeScope())) {
            for (Long s : new LinkedHashSet<>(f.storeIds()))
                jdbc.update("INSERT INTO offer_stores (offer_id, store_id) VALUES (?, ?)", id, s);
        }
        if (Offer.PRODUCT.equals(f.type())) {
            for (Long p : new LinkedHashSet<>(f.productIds()))
                jdbc.update("INSERT INTO offer_products (offer_id, product_id) VALUES (?, ?)", id, p);
        }
    }

    public void setEnabled(long id, boolean enabled, Long userId) {
        jdbc.update("UPDATE offers SET enabled = ?, updated_by = ? WHERE id = ? AND deleted_at IS NULL", enabled, userId, id);
        clearCache();
    }

    public void softDelete(long id, Long userId) {
        jdbc.update("UPDATE offers SET deleted_at = NOW(), enabled = 0, updated_by = ? WHERE id = ? AND deleted_at IS NULL", userId, id);
        clearCache();
    }

    public void audit(long offerId, Long userId, String action, Object before, Object after) {
        try {
            jdbc.update("INSERT INTO offer_audit (offer_id, user_id, action, before_json, after_json) VALUES (?,?,?,?,?)",
                offerId, userId, action,
                before == null ? null : mapper.writeValueAsString(before),
                after == null ? null : mapper.writeValueAsString(after));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the offer audit record", e);
        }
    }

    public Optional<Long> organizationOf(long offerId) {
        return jdbc.query("SELECT organization_id FROM offers WHERE id = ? AND deleted_at IS NULL",
            rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty(), offerId);
    }

    // Validation helpers: how many of these ids belong to the client.
    public int countStoresOf(long orgId, Collection<Long> ids) {
        if (ids.isEmpty()) return 0;
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(orgId));
        args.addAll(ids);
        return jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE organization_id = ? AND id IN (" + in + ")",
            Integer.class, args.toArray());
    }

    public int countProductsOf(long orgId, Collection<Long> ids) {
        if (ids.isEmpty()) return 0;
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(orgId));
        args.addAll(ids);
        return jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE company_id = ? AND id IN (" + in + ")",
            Integer.class, args.toArray());
    }

    // Active offers of a store with their products, for the kiosk badges.
    public Map<Long, Set<Long>> productIdsByOffer(List<Long> offerIds) {
        return offerIds.isEmpty() ? Map.of() : productsOf(offerIds);
    }
}
