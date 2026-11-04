package com.waha.coupon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

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
            int limit = rs.getInt("usage_limit");
            Integer usageLimit = rs.wasNull() ? null : limit;
            long emp = rs.getLong("employee_id");
            Long employeeId = rs.wasNull() ? null : emp;
            return new Coupon(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("offer_id"),
                rs.getString("code"), rs.getBoolean("enabled"),
                s == null ? null : s.toLocalDateTime(), e == null ? null : e.toLocalDateTime(), usageLimit,
                rs.getBigDecimal("minimum_order_amount"), rs.getBigDecimal("max_discount_per_order"), employeeId);
        }, organizationId, normalizedCode);
        return rows.stream().findFirst();
    }

    // Uses of a coupon: redemptions whose order has been paid. Unpaid and cancelled orders do not count.
    public long paidUsage(long couponId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM coupon_redemptions r JOIN orders o ON o.id = r.order_id "
            + "WHERE r.coupon_id = ? AND o.status = 'PAID'", Long.class, couponId);
        return n == null ? 0 : n;
    }

    // One row per order and coupon; a repeat for the same order changes nothing.
    public void recordRedemption(Coupon coupon, String orderId, String offerName, BigDecimal discountAmount) {
        jdbc.update("INSERT IGNORE INTO coupon_redemptions (coupon_id, offer_id, order_id, employee_id, code_snapshot, "
                + "offer_name_snapshot, discount_amount) VALUES (?,?,?,?,?,?,?)",
            coupon.id(), coupon.offerId(), orderId, coupon.employeeId(), coupon.code(), offerName, discountAmount);
    }
}
