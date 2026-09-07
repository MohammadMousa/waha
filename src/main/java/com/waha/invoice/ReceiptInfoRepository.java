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
        "organization_id, name_ar, name_en, address_text, vat_number, cr_number, logo_resource_id, " +
        "unpaid_invoice_title, paid_invoice_title";

    public Optional<ReceiptInfo> findByOrganizationId(long organizationId) {
        List<ReceiptInfo> results = jdbcTemplate.query(
            "SELECT " + SELECT_COLS + " FROM receipt_info WHERE organization_id = ?",
            (rs, i) -> mapRow(rs),
            organizationId
        );
        return results.stream().findFirst();
    }

    // Finds the receipt_info for whichever org owns the given store.
    // Used by InvoiceController which knows the order's storeId.
    public Optional<ReceiptInfo> findByStoreId(long storeId) {
        List<ReceiptInfo> results = jdbcTemplate.query(
            "SELECT ri.organization_id, ri.name_ar, ri.name_en, ri.address_text, ri.vat_number, " +
            "ri.cr_number, ri.logo_resource_id, ri.unpaid_invoice_title, ri.paid_invoice_title " +
            "FROM receipt_info ri JOIN stores s ON s.organization_id = ri.organization_id " +
            "WHERE s.id = ? LIMIT 1",
            (rs, i) -> mapRow(rs),
            storeId
        );
        return results.stream().findFirst();
    }

    public void upsert(long organizationId, Map<String, Object> fields) {
        boolean exists = !jdbcTemplate.query(
            "SELECT 1 FROM receipt_info WHERE organization_id = ?",
            (rs, i) -> rs.getInt(1), organizationId
        ).isEmpty();

        if (!exists) {
            jdbcTemplate.update(
                "INSERT INTO receipt_info (organization_id, name_ar, name_en) VALUES (?, '', '')",
                organizationId);
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
            params.add(organizationId);
            jdbcTemplate.update(
                "UPDATE receipt_info SET " + String.join(", ", setClauses) + " WHERE organization_id = ?",
                params.toArray()
            );
        }
    }

    public Map<String, Object> toResponse(ReceiptInfo info) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("organizationId", info.organizationId());
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
            rs.getLong("organization_id"),
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
