package com.waha.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

// Admin side of coupons: lists, details, changes and the redemption history.
@Repository
public class CouponAdminRepository {

    public record Page(List<Map<String, Object>> items, long total) {}

    public record Fields(String code, String name, String type, String discountMode, BigDecimal discountValue,
                         BigDecimal maxDiscountPerOrder, String storeScope, List<Long> storeIds, List<Long> productIds,
                         boolean enabled, LocalDateTime startsAt, LocalDateTime expiresAt,
                         Integer maxInvoices, Integer maxInvoicesPerUser, BigDecimal maxAmount,
                         BigDecimal maxAmountPerUser, BigDecimal minimumOrderAmount, boolean allowStackingWithOffers,
                         Long employeeId) {}

    private static final String USAGE_SQL = "(SELECT COUNT(*) FROM coupon_redemptions r JOIN orders od ON od.id = r.order_id "
        + "WHERE r.coupon_id = c.id AND od.status = 'PAID')";
    private static final String USED_SQL = "(SELECT COALESCE(SUM(r.discount_amount), 0) FROM coupon_redemptions r "
        + "JOIN orders od ON od.id = r.order_id WHERE r.coupon_id = c.id AND od.status = 'PAID')";

    // The derived status, from the coupon's own switch, dates and limits.
    private static final String SELECT_SQL = """
        SELECT c.*,
               COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name, e.last_name)), ''), e.username) AS employee_name,
               %s AS usage_count, %s AS used_amount,
               CASE WHEN c.enabled = 0 THEN 'DISABLED'
                    WHEN c.expires_at IS NOT NULL AND c.expires_at < :now THEN 'EXPIRED'
                    WHEN c.starts_at IS NOT NULL AND c.starts_at > :now THEN 'SCHEDULED'
                    WHEN (c.max_invoices IS NOT NULL AND %s >= c.max_invoices)
                      OR (c.max_amount IS NOT NULL AND %s >= c.max_amount) THEN 'EXHAUSTED'
                    ELSE 'ACTIVE' END AS status
        FROM coupons c
        LEFT JOIN employees e ON e.id = c.employee_id
        """.formatted(USAGE_SQL, USED_SQL, USAGE_SQL, USED_SQL);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CouponAdminRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static MapSqlParameterSource times(LocalDateTime now) {
        return new MapSqlParameterSource("now", Timestamp.valueOf(now));
    }

    public Page list(long org, String status, String q, Long employeeId, LocalDateTime now, int page, int size) {
        MapSqlParameterSource p = times(now).addValue("org", org);
        StringBuilder where = new StringBuilder(" WHERE c.organization_id = :org AND c.deleted_at IS NULL");
        if (q != null && !q.isBlank()) {
            where.append(" AND (c.code LIKE :q OR c.name LIKE :q OR CAST(c.id AS CHAR) = :exact)");
            p.addValue("q", "%" + q.trim() + "%").addValue("exact", q.trim());
        }
        if (employeeId != null) { where.append(" AND c.employee_id = :emp"); p.addValue("emp", employeeId); }
        String outer = "";
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("all")) {
            outer = " WHERE t.status = :status";
            p.addValue("status", status.trim().toUpperCase());
        }
        String inner = SELECT_SQL + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + inner + ") t" + outer, p, Long.class);
        p.addValue("lim", size).addValue("off", (long) page * size);
        List<Map<String, Object>> rows = jdbc.query("SELECT t.* FROM (" + inner + ") t" + outer
            + " ORDER BY t.id DESC LIMIT :lim OFFSET :off", p, (rs, i) -> row(rs));
        return new Page(rows, total == null ? 0 : total);
    }

    public Optional<Map<String, Object>> find(long org, long id, LocalDateTime now) {
        List<Map<String, Object>> rows = jdbc.query(SELECT_SQL + " WHERE c.id = :id AND c.organization_id = :org AND c.deleted_at IS NULL",
            times(now).addValue("id", id).addValue("org", org), (rs, i) -> row(rs));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> m = rows.get(0);
        m.put("storeIds", jdbc.queryForList("SELECT store_id FROM coupon_stores WHERE coupon_id = :id ORDER BY store_id", Map.of("id", id), Long.class));
        m.put("productIds", jdbc.queryForList("SELECT product_id FROM coupon_products WHERE coupon_id = :id ORDER BY product_id", Map.of("id", id), Long.class));
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("paidUses", m.get("usageCount"));
        usage.put("pendingOrders", jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions r JOIN orders od ON od.id = r.order_id "
            + "WHERE r.coupon_id = :id AND od.status <> 'PAID' AND od.status <> 'CANCELLED'", Map.of("id", id), Long.class));
        usage.put("totalDiscountGiven", jdbc.queryForObject("SELECT COALESCE(SUM(r.discount_amount), 0) FROM coupon_redemptions r "
            + "JOIN orders od ON od.id = r.order_id WHERE r.coupon_id = :id AND od.status = 'PAID'", Map.of("id", id), BigDecimal.class));
        m.put("usage", usage);
        return Optional.of(m);
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("code", rs.getString("code"));
        m.put("name", rs.getString("name"));
        m.put("type", rs.getString("type"));
        m.put("discountMode", rs.getString("discount_mode"));
        m.put("discountValue", rs.getBigDecimal("discount_value"));
        m.put("discountSummary", summary(rs.getString("discount_mode"), rs.getBigDecimal("discount_value")));
        m.put("maxDiscountPerOrder", rs.getBigDecimal("max_discount_per_order"));
        m.put("storeScope", rs.getString("store_scope"));
        m.put("enabled", rs.getBoolean("enabled"));
        m.put("startsAt", local(rs.getTimestamp("starts_at")));
        m.put("expiresAt", local(rs.getTimestamp("expires_at")));
        m.put("maxInvoices", nullableInt(rs, "max_invoices"));
        m.put("maxInvoicesPerUser", nullableInt(rs, "max_invoices_per_user"));
        m.put("maxAmount", rs.getBigDecimal("max_amount"));
        m.put("maxAmountPerUser", rs.getBigDecimal("max_amount_per_user"));
        m.put("minimumOrderAmount", rs.getBigDecimal("minimum_order_amount"));
        m.put("allowStackingWithOffers", rs.getBoolean("allow_stacking_with_offers"));
        m.put("employeeId", rs.getObject("employee_id") == null ? null : rs.getLong("employee_id"));
        m.put("employeeName", rs.getString("employee_name"));
        m.put("usageCount", rs.getLong("usage_count"));
        m.put("usedAmount", rs.getBigDecimal("used_amount"));
        m.put("status", rs.getString("status"));
        m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        return m;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static String summary(String mode, BigDecimal value) {
        if (value == null) return "";
        BigDecimal v = value.stripTrailingZeros();
        return "PERCENTAGE".equals(mode) ? v.toPlainString() + "%" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String local(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().withNano(0).toString();
    }

    // ── checks ──────────────────────────────────────────────────────────────

    public boolean codeTaken(long org, String normalized, Long exceptId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM coupons WHERE organization_id = :org AND code_normalized = :code "
            + "AND (:except IS NULL OR id <> :except)",
            new MapSqlParameterSource("org", org).addValue("code", normalized).addValue("except", exceptId), Integer.class);
        return n != null && n > 0;
    }

    public boolean employeeInOrg(long employeeId, long org) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE id = :id AND organization_id = :org",
            Map.of("id", employeeId, "org", org), Integer.class);
        return n != null && n > 0;
    }

    public boolean hasRedemptions(long couponId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = :id", Map.of("id", couponId), Integer.class);
        return n != null && n > 0;
    }

    // ── changes ─────────────────────────────────────────────────────────────

    private static MapSqlParameterSource params(long org, Fields f, Long userId) {
        return new MapSqlParameterSource("org", org).addValue("code", f.code()).addValue("name", f.name())
            .addValue("norm", Coupon.normalize(f.code())).addValue("type", f.type()).addValue("mode", f.discountMode())
            .addValue("value", f.discountValue()).addValue("cap", f.maxDiscountPerOrder()).addValue("scope", f.storeScope())
            .addValue("enabled", f.enabled())
            .addValue("starts", f.startsAt() == null ? null : Timestamp.valueOf(f.startsAt()))
            .addValue("expires", f.expiresAt() == null ? null : Timestamp.valueOf(f.expiresAt()))
            .addValue("inv", f.maxInvoices()).addValue("invUser", f.maxInvoicesPerUser())
            .addValue("amt", f.maxAmount()).addValue("amtUser", f.maxAmountPerUser())
            .addValue("min", f.minimumOrderAmount()).addValue("stack", f.allowStackingWithOffers())
            .addValue("emp", f.employeeId()).addValue("user", userId);
    }

    public long insert(long org, Fields f, Long userId) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("INSERT INTO coupons (organization_id, code, code_normalized, name, type, discount_mode, discount_value, "
            + "max_discount_per_order, store_scope, enabled, starts_at, expires_at, max_invoices, max_invoices_per_user, "
            + "max_amount, max_amount_per_user, minimum_order_amount, allow_stacking_with_offers, employee_id, created_by, updated_by) "
            + "VALUES (:org,:code,:norm,:name,:type,:mode,:value,:cap,:scope,:enabled,:starts,:expires,:inv,:invUser,"
            + ":amt,:amtUser,:min,:stack,:emp,:user,:user)", params(org, f, userId), keys);
        long id = keys.getKey().longValue();
        saveTargets(id, f);
        return id;
    }

    public void update(long org, long id, Fields f, Long userId) {
        jdbc.update("UPDATE coupons SET code = :code, code_normalized = :norm, name = :name, type = :type, "
            + "discount_mode = :mode, discount_value = :value, max_discount_per_order = :cap, store_scope = :scope, "
            + "enabled = :enabled, starts_at = :starts, expires_at = :expires, max_invoices = :inv, "
            + "max_invoices_per_user = :invUser, max_amount = :amt, max_amount_per_user = :amtUser, "
            + "minimum_order_amount = :min, allow_stacking_with_offers = :stack, employee_id = :emp, updated_by = :user "
            + "WHERE id = :id AND organization_id = :org AND deleted_at IS NULL",
            params(org, f, userId).addValue("id", id));
        saveTargets(id, f);
    }

    private void saveTargets(long id, Fields f) {
        jdbc.update("DELETE FROM coupon_stores WHERE coupon_id = :id", Map.of("id", id));
        jdbc.update("DELETE FROM coupon_products WHERE coupon_id = :id", Map.of("id", id));
        for (Long s : f.storeIds())
            jdbc.update("INSERT INTO coupon_stores (coupon_id, store_id) VALUES (:id,:s)", Map.of("id", id, "s", s));
        for (Long p : f.productIds())
            jdbc.update("INSERT INTO coupon_products (coupon_id, product_id) VALUES (:id,:p)", Map.of("id", id, "p", p));
    }

    public void setEnabled(long id, boolean enabled, Long userId) {
        jdbc.update("UPDATE coupons SET enabled = :e, updated_by = :u WHERE id = :id AND deleted_at IS NULL",
            new MapSqlParameterSource("e", enabled).addValue("id", id).addValue("u", userId));
    }

    // The code is freed for reuse; the redemption history keeps its snapshots.
    public void softDelete(long id, Long userId) {
        jdbc.update("UPDATE coupons SET deleted_at = NOW(), enabled = 0, updated_by = :u, "
            + "code_normalized = CONCAT(code_normalized, '#del', id) WHERE id = :id AND deleted_at IS NULL",
            new MapSqlParameterSource("id", id).addValue("u", userId));
    }

    public void audit(long couponId, Long userId, String action, Object before, Object after) {
        try {
            jdbc.update("INSERT INTO coupon_audit (coupon_id, user_id, action, before_json, after_json) VALUES (:c,:u,:a,:b,:f)",
                new MapSqlParameterSource("c", couponId).addValue("u", userId).addValue("a", action)
                    .addValue("b", before == null ? null : mapper.writeValueAsString(before))
                    .addValue("f", after == null ? null : mapper.writeValueAsString(after)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the coupon audit record", e);
        }
    }

    // ── redemptions ─────────────────────────────────────────────────────────

    public Page redemptions(long couponId, int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = :id", Map.of("id", couponId), Long.class);
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT r.order_id, od.display_id, s.name AS store_name, od.username, od.status, od.total_amount,
                   r.discount_amount, r.code_snapshot, r.name_snapshot, r.created_at
            FROM coupon_redemptions r
            JOIN orders od ON od.id = r.order_id
            JOIN stores s ON s.id = od.store_id
            WHERE r.coupon_id = :id ORDER BY r.id DESC LIMIT :lim OFFSET :off
            """, new MapSqlParameterSource("id", couponId).addValue("lim", size).addValue("off", (long) page * size), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", rs.getString("order_id"));
            m.put("displayId", rs.getObject("display_id") == null ? null : rs.getLong("display_id"));
            m.put("storeName", rs.getString("store_name"));
            m.put("username", rs.getString("username"));
            m.put("orderStatus", rs.getString("status"));
            m.put("orderTotal", rs.getBigDecimal("total_amount"));
            m.put("discountAmount", rs.getBigDecimal("discount_amount"));
            m.put("code", rs.getString("code_snapshot"));
            m.put("name", rs.getString("name_snapshot"));
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return m;
        });
        return new Page(rows, total == null ? 0 : total);
    }
}
