package com.waha.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// One paged "changed since" query per synced table. Rows come back ordered by (updated_at, id) and
// inactive rows are included, so a deactivated product reaches the device. Every query is limited to
// the caller's organization (or store for the per-store table).
@Repository
public class SyncFeedRepository {

    // A feed row: the cursor columns plus the JSON-ready fields.
    public record Row(Instant updatedAt, long id, Map<String, Object> fields) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SyncFeedRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // Keyset filter: strictly after the cursor (since, afterId). Without a cursor everything matches.
    private static final String AFTER =
        " AND (:since IS NULL OR %1$s.updated_at > :since OR (%1$s.updated_at = :since AND %1$s.id > :afterId))";

    private Map<String, Object> params(long scopeId, Instant since, long afterId, int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("scope", scopeId);
        p.put("since", since == null ? null : Timestamp.from(since));
        p.put("afterId", afterId);
        p.put("limit", limit);
        return p;
    }

    public List<Row> products(long orgId, Instant since, long afterId, int limit, java.math.BigDecimal vatRate) {
        return jdbc.query(
            "SELECT id, barcode, sku, name, description, price, active, `public`, category_id, image_resource_id, updated_at " +
            "FROM products p WHERE p.company_id = :scope" + AFTER.formatted("p") +
            " ORDER BY p.updated_at, p.id LIMIT :limit",
            params(orgId, since, afterId, limit),
            (rs, i) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                BigDecimal price = rs.getBigDecimal("price");
                f.put("id", rs.getLong("id"));
                f.put("barcode", rs.getString("barcode"));
                f.put("sku", rs.getString("sku"));
                f.put("name", json(rs.getString("name")));
                f.put("description", json(rs.getString("description")));
                f.put("price", price);
                f.put("priceWithVat", com.waha.pricing.VatPricing.withVat(price, vatRate));
                f.put("active", rs.getBoolean("active"));
                f.put("public", rs.getBoolean("public"));
                f.put("categoryId", nullableLong(rs, "category_id"));
                f.put("imageResourceId", nullableLong(rs, "image_resource_id"));
                return row(rs, f);
            });
    }

    public List<Row> barcodes(long orgId, Instant since, long afterId, int limit) {
        return jdbc.query(
            "SELECT b.id, b.product_id, b.barcode, b.is_primary, b.updated_at " +
            "FROM product_barcodes b JOIN products p ON p.id = b.product_id " +
            "WHERE p.company_id = :scope" + AFTER.formatted("b") +
            " ORDER BY b.updated_at, b.id LIMIT :limit",
            params(orgId, since, afterId, limit),
            (rs, i) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", rs.getLong("id"));
                f.put("productId", rs.getLong("product_id"));
                f.put("barcode", rs.getString("barcode"));
                f.put("primary", rs.getBoolean("is_primary"));
                return row(rs, f);
            });
    }

    public List<Row> categories(long orgId, Instant since, long afterId, int limit) {
        return jdbc.query(
            "SELECT id, name, `key`, `public`, active, sort_order, image_resource_id, updated_at " +
            "FROM categories c WHERE c.company_id = :scope" + AFTER.formatted("c") +
            " ORDER BY c.updated_at, c.id LIMIT :limit",
            params(orgId, since, afterId, limit),
            (rs, i) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", rs.getLong("id"));
                f.put("name", json(rs.getString("name")));
                f.put("key", rs.getString("key"));
                f.put("public", rs.getBoolean("public"));
                f.put("active", rs.getBoolean("active"));
                f.put("sortOrder", rs.getInt("sort_order"));
                f.put("imageResourceId", nullableLong(rs, "image_resource_id"));
                return row(rs, f);
            });
    }

    // Payment methods are global (not per organization).
    public List<Row> paymentMethods(Instant since, long afterId, int limit) {
        return jdbc.query(
            "SELECT id, `key`, display_name, provider, available_modes, offline_capable, active, sort_order, payment_url, updated_at " +
            "FROM payment_methods m WHERE 1 = 1" + AFTER.formatted("m") +
            " ORDER BY m.updated_at, m.id LIMIT :limit",
            params(0, since, afterId, limit),
            (rs, i) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", rs.getLong("id"));
                f.put("key", rs.getString("key"));
                f.put("displayName", json(rs.getString("display_name")));
                f.put("provider", rs.getString("provider"));
                f.put("availableModes", rs.getString("available_modes"));
                f.put("offlineCapable", rs.getBoolean("offline_capable"));
                f.put("active", rs.getBoolean("active"));
                f.put("sortOrder", rs.getInt("sort_order"));
                f.put("paymentUrl", rs.getString("payment_url"));
                return row(rs, f);
            });
    }

    public List<Row> storePaymentMethods(long storeId, Instant since, long afterId, int limit) {
        return jdbc.query(
            "SELECT id, payment_method_id, active, sort_order, updated_at " +
            "FROM payment_methods_store s WHERE s.store_id = :scope" + AFTER.formatted("s") +
            " ORDER BY s.updated_at, s.id LIMIT :limit",
            params(storeId, since, afterId, limit),
            (rs, i) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", rs.getLong("id"));
                f.put("paymentMethodId", rs.getLong("payment_method_id"));
                f.put("active", rs.getBoolean("active"));
                f.put("sortOrder", nullableInt(rs, "sort_order"));
                return row(rs, f);
            });
    }

    public List<Row> productImages(long orgId, Instant since, long afterId, int limit) {
        return jdbc.query(
            "SELECT i.id, i.product_id, i.resource_id, i.sort_order, i.updated_at " +
            "FROM product_images i JOIN products p ON p.id = i.product_id " +
            "WHERE p.company_id = :scope" + AFTER.formatted("i") +
            " ORDER BY i.updated_at, i.id LIMIT :limit",
            params(orgId, since, afterId, limit),
            (rs, i) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", rs.getLong("id"));
                f.put("productId", rs.getLong("product_id"));
                f.put("resourceId", rs.getLong("resource_id"));
                f.put("sortOrder", rs.getInt("sort_order"));
                return row(rs, f);
            });
    }

    private static Row row(ResultSet rs, Map<String, Object> fields) throws SQLException {
        Instant updated = rs.getTimestamp("updated_at").toInstant();
        fields.put("updatedAt", updated);
        return new Row(updated, ((Number) fields.get("id")).longValue(), fields);
    }

    private JsonNode json(String raw) {
        if (raw == null) return null;
        try { return mapper.readTree(raw); } catch (Exception e) { return null; }
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
