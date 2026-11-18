package com.waha.order;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

// Deletes unpaid orders together with everything attached to them: items, status history, payment attempts
// and failed payments, offer applications and coupon records. A paid order is never deleted, and neither is an
// order with a payment attempt still in progress. Used by the daily cleanup and by the manual delete calls.
@Repository
public class UnpaidOrderRepository {

    // Safe to delete: not PAID, no paid payment, no payment attempt still running.
    static final String DELETABLE =
        "o.status <> 'PAID' "
        + "AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.order_id = o.id AND p.outcome = 'PAID') "
        + "AND NOT EXISTS (SELECT 1 FROM payment_attempts a WHERE a.order_id = o.id AND a.expires_at > NOW())";

    private final NamedParameterJdbcTemplate jdbc;

    public UnpaidOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Orders that are not PAID (all ages), for the cleanup status screen.
    public long countUnpaid() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status <> 'PAID'", new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    // Deletable unpaid orders older than N days. orgId and storeId are optional filters.
    public long countOlderThan(int days, Long orgId, Long storeId) {
        MapSqlParameterSource p = new MapSqlParameterSource("days", days);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders o WHERE " + DELETABLE
            + " AND o.created_at < NOW() - INTERVAL :days DAY" + filters(orgId, storeId, p), p, Long.class);
        return n == null ? 0 : n;
    }

    // Ids of deletable unpaid orders older than N days, oldest first.
    public List<String> findOlderThan(int days, Long orgId, Long storeId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("days", days).addValue("limit", limit);
        return jdbc.queryForList("SELECT o.id FROM orders o WHERE " + DELETABLE
            + " AND o.created_at < NOW() - INTERVAL :days DAY" + filters(orgId, storeId, p)
            + " ORDER BY o.created_at LIMIT :limit", p, String.class);
    }

    private static String filters(Long orgId, Long storeId, MapSqlParameterSource p) {
        StringBuilder sb = new StringBuilder();
        if (orgId != null && orgId > 0) { sb.append(" AND o.organization_id = :org"); p.addValue("org", orgId); }
        if (storeId != null) { sb.append(" AND o.store_id = :store"); p.addValue("store", storeId); }
        return sb.toString();
    }

    // Deletes the given orders that are still deletable (and belong to orgId when given). Returns how many were deleted;
    // the rest were paid, in progress, missing or in another organization.
    @Transactional
    public int delete(Collection<String> ids, Long orgId) {
        if (ids.isEmpty()) return 0;
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids);
        // Lock the rows that qualify so a payment cannot slip in between the check and the delete.
        List<String> safe = jdbc.queryForList("SELECT o.id FROM orders o WHERE o.id IN (:ids) AND " + DELETABLE
            + filters(orgId, null, p) + " FOR UPDATE", p, String.class);
        if (safe.isEmpty()) return 0;
        MapSqlParameterSource s = new MapSqlParameterSource("ids", safe);
        for (String table : new String[]{"coupon_redemptions", "offer_applications", "order_items",
                                         "order_status_history", "payment_attempts", "payments"}) {
            jdbc.update("DELETE FROM " + table + " WHERE order_id IN (:ids)", s);
        }
        return jdbc.update("DELETE FROM orders WHERE id IN (:ids)", s);
    }
}
