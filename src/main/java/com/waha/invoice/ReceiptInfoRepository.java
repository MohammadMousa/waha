package com.waha.invoice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public void upsert(long storeId, Map<String, Object> fields) {
        boolean exists = !jdbcTemplate.query(
            "SELECT 1 FROM receipt_info WHERE store_id = ?",
            (rs, i) -> rs.getInt(1), storeId
        ).isEmpty();

        if (!exists) {
            jdbcTemplate.update("INSERT INTO receipt_info (store_id) VALUES (?)", storeId);
        }

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        String[][] mappings = {
            {"nameAr", "name_ar"}, {"nameEn", "name_en"},
            {"addressText", "address_text"}, {"vatNumber", "vat_number"},
            {"crNumber", "cr_number"}, {"unpaidInvoiceTitle", "unpaid_invoice_title"},
            {"paidInvoiceTitle", "paid_invoice_title"}
        };
        for (String[] pair : mappings) {
            if (fields.containsKey(pair[0])) {
                setClauses.add(pair[1] + " = ?");
                Object v = fields.get(pair[0]);
                params.add(v instanceof String s && s.isBlank() ? null : v);
            }
        }
        if (fields.containsKey("logoResourceId")) {
            setClauses.add("logo_resource_id = ?");
            Object v = fields.get("logoResourceId");
            params.add(v == null ? null : ((Number) v).longValue());
        }

        if (!setClauses.isEmpty()) {
            params.add(storeId);
            jdbcTemplate.update(
                "UPDATE receipt_info SET " + String.join(", ", setClauses) + " WHERE store_id = ?",
                params.toArray()
            );
        }
    }

    public Map<String, Object> toResponse(ReceiptInfo info) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("storeId", info.storeId());
        m.put("nameAr", info.nameAr());
        m.put("nameEn", info.nameEn());
        m.put("addressText", info.addressText());
        m.put("vatNumber", info.vatNumber());
        m.put("crNumber", info.crNumber());
        m.put("logoResourceId", info.logoResourceId());
        m.put("unpaidInvoiceTitle", info.unpaidInvoiceTitle());
        m.put("paidInvoiceTitle", info.paidInvoiceTitle());
        return m;
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
