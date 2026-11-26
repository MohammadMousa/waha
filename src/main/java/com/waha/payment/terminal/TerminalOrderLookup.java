package com.waha.payment.terminal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// Which store and organization an order belongs to, found by the order or by one of its terminal attempts.
@Repository
public class TerminalOrderLookup {

    public record Owner(String orderId, long storeId, long organizationId) {}

    private final JdbcTemplate jdbc;

    public TerminalOrderLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Owner> byOrder(String orderId) {
        return one("SELECT o.id, o.store_id, o.organization_id FROM orders o WHERE o.id = ?", orderId);
    }

    public Optional<Owner> byAttempt(String attemptId) {
        return one("SELECT o.id, o.store_id, o.organization_id FROM payment_attempts pa " +
                   "JOIN orders o ON o.id = pa.order_id WHERE pa.id = ?", attemptId);
    }

    private Optional<Owner> one(String sql, String id) {
        List<Owner> rows = jdbc.query(sql,
            (rs, i) -> new Owner(rs.getString("id"), rs.getLong("store_id"), rs.getLong("organization_id")), id);
        return rows.stream().findFirst();
    }
}
