package com.waha.invoice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ReceiptInfoRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReceiptInfoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String SELECT_COLS =
        "store_id, name_ar, name_en, address_text, vat_number, cr_number, logo_resource_id, " +
        "unpaid_invoice_title, paid_invoice_title";

    // Exact match — kept for single-store lookups where the scope chain isn't available.
    public Optional<ReceiptInfo> findByStoreId(long storeId) {
        List<ReceiptInfo> results = jdbcTemplate.query(
            "SELECT " + SELECT_COLS + " FROM receipt_info WHERE store_id = ?",
            (rs, i) -> mapRow(rs),
            storeId
        );
        return results.stream().findFirst();
    }

    // Walks the ancestor chain (most-specific store first) and returns the
    // first receipt_info row found. If the kiosk's own store has no branding
    // row, we show its parent's — same specificity logic as product scope.
    // scopeChain is [storeId, parentId, ..., rootId] from StoreRepository.
    public Optional<ReceiptInfo> findByStoreChain(List<Long> scopeChain) {
        if (scopeChain.isEmpty()) return Optional.empty();

        // Build IN clause (safe: IDs are longs, not user-supplied strings)
        StringBuilder inClause = new StringBuilder();
        StringBuilder rankCase = new StringBuilder("CASE store_id ");
        for (int i = 0; i < scopeChain.size(); i++) {
            if (i > 0) inClause.append(", ");
            inClause.append(scopeChain.get(i));
            rankCase.append("WHEN ").append(scopeChain.get(i)).append(" THEN ").append(i).append(' ');
        }
        rankCase.append("ELSE ").append(scopeChain.size()).append(" END");

        List<ReceiptInfo> results = jdbcTemplate.query(
            "SELECT " + SELECT_COLS + " FROM receipt_info WHERE store_id IN (" + inClause + ") " +
            "ORDER BY " + rankCase + " LIMIT 1",
            (rs, i) -> mapRow(rs)
        );
        return results.stream().findFirst();
    }

    private ReceiptInfo mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        long logoId = rs.getLong("logo_resource_id");
        Long logoResourceId = rs.wasNull() ? null : logoId;
        return new ReceiptInfo(
            rs.getLong("store_id"),
            rs.getString("name_ar"),
            rs.getString("name_en"),
            rs.getString("address_text"),
            rs.getString("vat_number"),
            rs.getString("cr_number"),
            logoResourceId,
            rs.getString("unpaid_invoice_title"),
            rs.getString("paid_invoice_title")
        );
    }
}
