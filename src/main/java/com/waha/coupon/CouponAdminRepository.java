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

    public record Fields(String code, long offerId, boolean enabled, LocalDateTime startsAt, LocalDateTime expiresAt,
                         Integer usageLimit, BigDecimal minimumOrderAmount, BigDecimal maxDiscountPerOrder,
                         Long employeeId) {}

    private static final String USAGE_SQL = "(SELECT COUNT(*) FROM coupon_redemptions r JOIN orders od ON od.id = r.order_id "
        + "WHERE r.coupon_id = c.id AND od.status = 'PAID')";

    // The derived status, from the coupon's own switch and dates, its offer's dates and its usage.
    private static final String SELECT_SQL = """
        SELECT c.id, c.organization_id, c.offer_id, c.code, c.enabled, c.starts_at, c.expires_at, c.usage_limit,
               c.minimum_order_amount, c.max_discount_per_order, c.employee_id, c.created_at, c.updated_at,
               o.name AS offer_name, o.type AS offer_type, o.discount_mode, o.discount_value, o.max_discount_amount,
               o.enabled AS offer_enabled, o.start_date, o.end_date, o.store_scope,
               COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name, e.last_name)), ''), e.username) AS employee_name,
               %s AS usage_count,
               CASE WHEN c.enabled = 0 THEN 'DISABLED'
                    WHEN (c.expires_at IS NOT NULL AND c.expires_at < :now) OR o.end_date < :today THEN 'EXPIRED'
                    WHEN (c.starts_at IS NOT NULL AND c.starts_at > :now) OR o.start_date > :today THEN 'SCHEDULED'
                    WHEN c.usage_limit IS NOT NULL AND %s >= c.usage_limit THEN 'EXHAUSTED'
                    WHEN o.enabled = 0 THEN 'OFFER_DISABLED'
                    ELSE 'ACTIVE' END AS status
        FROM coupons c
        JOIN offers o ON o.id = c.offer_id
        LEFT JOIN employees e ON e.id = c.employee_id
        """.formatted(USAGE_SQL, USAGE_SQL);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CouponAdminRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static MapSqlParameterSource times(LocalDateTime now) {
        return new MapSqlParameterSource("now", Timestamp.valueOf(now)).addValue("today", java.sql.Date.valueOf(now.toLocalDate()));
    }

    public Page list(long org, String status, String q, Long offerId, Long employeeId, LocalDateTime now, int page, int size) {
        MapSqlParameterSource p = times(now).addValue("org", org);
        StringBuilder where = new StringBuilder(" WHERE c.organization_id = :org AND c.deleted_at IS NULL");
        if (q != null && !q.isBlank()) {
            where.append(" AND (c.code LIKE :q OR o.name LIKE :q OR CAST(c.id AS CHAR) = :exact)");
            p.addValue("q", "%" + q.trim() + "%").addValue("exact", q.trim());
        }
        if (offerId != null) { where.append(" AND c.offer_id = :offer"); p.addValue("offer", offerId); }
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
        m.put("offerId", rs.getLong("offer_id"));
        m.put("offerName", rs.getString("offer_name"));
        m.put("offerType", rs.getString("offer_type"));
        m.put("discountMode", rs.getString("discount_mode"));
        m.put("discountValue", rs.getBigDecimal("discount_value"));
        m.put("discountSummary", summary(rs.getString("discount_mode"), rs.getBigDecimal("discount_value")));
        m.put("enabled", rs.getBoolean("enabled"));
        m.put("startsAt", local(rs.getTimestamp("starts_at")));
        m.put("expiresAt", local(rs.getTimestamp("expires_at")));
        m.put("usageLimit", rs.getObject("usage_limit") == null ? null : rs.getInt("usage_limit"));
        m.put("usageCount", rs.getLong("usage_count"));
        m.put("minimumOrderAmount", rs.getBigDecimal("minimum_order_amount"));
        m.put("maxDiscountPerOrder", rs.getBigDecimal("max_discount_per_order"));
        m.put("employeeId", rs.getObject("employee_id") == null ? null : rs.getLong("employee_id"));
        m.put("employeeName", rs.getString("employee_name"));
        m.put("status", rs.getString("status"));
        m.put("offerStartDate", rs.getDate("start_date").toLocalDate().toString());
        m.put("offerEndDate", rs.getDate("end_date").toLocalDate().toString());
        m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        m.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        return m;
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
        return new MapSqlParameterSource("org", org).addValue("offer", f.offerId()).addValue("code", f.code())
            .addValue("norm", Coupon.normalize(f.code())).addValue("enabled", f.enabled())
            .addValue("starts", f.startsAt() == null ? null : Timestamp.valueOf(f.startsAt()))
            .addValue("expires", f.expiresAt() == null ? null : Timestamp.valueOf(f.expiresAt()))
            .addValue("limit", f.usageLimit()).addValue("min", f.minimumOrderAmount())
            .addValue("max", f.maxDiscountPerOrder()).addValue("emp", f.employeeId()).addValue("user", userId);
    }

    public long insert(long org, Fields f, Long userId) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("INSERT INTO coupons (organization_id, offer_id, code, code_normalized, enabled, starts_at, expires_at, "
            + "usage_limit, minimum_order_amount, max_discount_per_order, employee_id, created_by, updated_by) "
            + "VALUES (:org,:offer,:code,:norm,:enabled,:starts,:expires,:limit,:min,:max,:emp,:user,:user)",
            params(org, f, userId), keys);
        return keys.getKey().longValue();
    }

    public void update(long org, long id, Fields f, Long userId) {
        jdbc.update("UPDATE coupons SET offer_id = :offer, code = :code, code_normalized = :norm, enabled = :enabled, "
            + "starts_at = :starts, expires_at = :expires, usage_limit = :limit, minimum_order_amount = :min, "
            + "max_discount_per_order = :max, employee_id = :emp, updated_by = :user "
            + "WHERE id = :id AND organization_id = :org AND deleted_at IS NULL",
            params(org, f, userId).addValue("id", id));
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
                   r.discount_amount, r.code_snapshot, r.offer_name_snapshot, r.created_at
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
            m.put("offerName", rs.getString("offer_name_snapshot"));
            m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return m;
        });
        return new Page(rows, total == null ? 0 : total);
    }
}
