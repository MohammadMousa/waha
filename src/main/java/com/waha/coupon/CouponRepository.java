package com.waha.coupon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class CouponRepository {

    private final JdbcTemplate jdbc;

    public CouponRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // A coupon that is not deleted, by its code within one organization.
    public Optional<Coupon> findByCode(long organizationId, String normalizedCode) {
        List<Coupon> rows = jdbc.query("SELECT * FROM coupons WHERE organization_id = ? AND code_normalized = ? "
            + "AND deleted_at IS NULL", (rs, i) -> {
            Timestamp s = rs.getTimestamp("starts_at"), e = rs.getTimestamp("expires_at");
            long id = rs.getLong("id");
            long emp = rs.getLong("employee_id");
            Long employeeId = rs.wasNull() ? null : emp;
            return new Coupon(id, rs.getLong("organization_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("type"), rs.getString("discount_mode"), rs.getBigDecimal("discount_value"),
                rs.getBigDecimal("max_discount_per_order"), rs.getString("store_scope"),
                ids("SELECT store_id FROM coupon_stores WHERE coupon_id = ?", id),
                ids("SELECT product_id FROM coupon_products WHERE coupon_id = ?", id),
                rs.getBoolean("enabled"), s == null ? null : s.toLocalDateTime(), e == null ? null : e.toLocalDateTime(),
                integer(rs, "max_invoices"), integer(rs, "max_invoices_per_user"),
                rs.getBigDecimal("max_amount"), rs.getBigDecimal("max_amount_per_user"),
                rs.getBigDecimal("minimum_order_amount"), rs.getBoolean("allow_stacking_with_offers"), employeeId);
        }, organizationId, normalizedCode);
        return rows.stream().findFirst();
    }

    private static Integer integer(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private Set<Long> ids(String sql, long couponId) {
        return new HashSet<>(jdbc.queryForList(sql, Long.class, couponId));
    }

    private static final String PAID = "FROM coupon_redemptions r JOIN orders o ON o.id = r.order_id "
        + "WHERE r.coupon_id = ? AND o.status = 'PAID'";

    // Uses of a coupon: redemptions whose order has been paid. Unpaid and cancelled orders do not count.
    public long paidInvoices(long couponId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + PAID, Long.class, couponId);
        return n == null ? 0 : n;
    }

    public long paidInvoices(long couponId, String username) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + PAID + " AND o.username = ?", Long.class, couponId, username);
        return n == null ? 0 : n;
    }

    // Discount given so far on paid orders.
    public BigDecimal paidAmount(long couponId) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(r.discount_amount), 0) " + PAID, BigDecimal.class, couponId);
        return v == null ? BigDecimal.ZERO : v;
    }

    public BigDecimal paidAmount(long couponId, String username) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(r.discount_amount), 0) " + PAID + " AND o.username = ?",
            BigDecimal.class, couponId, username);
        return v == null ? BigDecimal.ZERO : v;
    }

    // One row per order and coupon; a repeat for the same order changes nothing.
    public void recordRedemption(Coupon coupon, String orderId, BigDecimal discountAmount) {
        jdbc.update("INSERT IGNORE INTO coupon_redemptions (coupon_id, order_id, employee_id, code_snapshot, "
                + "name_snapshot, discount_amount) VALUES (?,?,?,?,?,?)",
            coupon.id(), orderId, coupon.employeeId(), coupon.code(), coupon.name(), discountAmount);
    }
}
