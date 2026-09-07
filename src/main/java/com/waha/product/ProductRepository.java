package com.waha.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.product.dto.ProductSyncItem;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private Product mapProduct(ResultSet rs) throws SQLException {
        long catId = rs.getLong("category_id");
        Long categoryId = rs.wasNull() ? null : catId;
        long imgId = rs.getLong("image_resource_id");
        Long imageResourceId = rs.wasNull() ? null : imgId;
        return new Product(
            rs.getLong("id"),
            rs.getString("barcode"),
            parseJson(rs.getString("name")),
            parseJson(rs.getString("description")),
            rs.getBigDecimal("price"),
            rs.getBoolean("active"),
            rs.getLong("company_id"),
            rs.getBoolean("public"),
            categoryId,
            imageResourceId
        );
    }

    private ProductSyncItem mapSyncItem(ResultSet rs) throws SQLException {
        long imgId = rs.getLong("image_resource_id");
        Long imageResourceId = rs.wasNull() ? null : imgId;
        return new ProductSyncItem(
            rs.getLong("id"),
            rs.getString("barcode"),
            parseJson(rs.getString("name")),
            parseJson(rs.getString("description")),
            rs.getBigDecimal("price"),
            rs.getBoolean("active"),
            imageResourceId,
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private JsonNode parseJson(String raw) {
        if (raw == null) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static final String PRODUCT_COLS =
        "id, barcode, name, description, price, active, company_id, `public`, category_id, image_resource_id";

    public Optional<Product> resolveByBarcode(String barcode, long companyId) {
        List<Product> results = jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM products " +
            "WHERE barcode = :barcode AND company_id = :companyId LIMIT 1",
            Map.of("barcode", barcode, "companyId", companyId),
            (rs, i) -> mapProduct(rs)
        );
        return results.stream().findFirst();
    }

    public Optional<Product> findById(long id) {
        List<Product> results = jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM products WHERE id = :id",
            Map.of("id", id), (rs, i) -> mapProduct(rs)
        );
        return results.stream().findFirst();
    }

    public List<String> findTagsByProduct(long productId) {
        return jdbc.queryForList(
            "SELECT tag FROM product_tags WHERE product_id = :id ORDER BY tag",
            Map.of("id", productId), String.class
        );
    }

    public void syncTags(long productId, List<String> tags) {
        jdbc.getJdbcTemplate().update("DELETE FROM product_tags WHERE product_id = ?", productId);
        if (tags == null || tags.isEmpty()) return;
        for (String tag : tags) {
            String trimmed = tag == null ? null : tag.trim();
            if (trimmed != null && !trimmed.isEmpty()) {
                jdbc.getJdbcTemplate().update(
                    "INSERT IGNORE INTO product_tags (product_id, tag) VALUES (?, ?)",
                    productId, trimmed
                );
            }
        }
    }

    public void patch(long id, com.fasterxml.jackson.databind.JsonNode body) {
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (body.has("name")) {
            setClauses.add("name = ?");
            params.add(body.get("name").toString());
        }
        if (body.has("description")) {
            setClauses.add("description = ?");
            JsonNode desc = body.get("description");
            params.add(desc.isNull() ? null : desc.toString());
        }
        if (body.has("imageResourceId")) {
            setClauses.add("image_resource_id = ?");
            JsonNode img = body.get("imageResourceId");
            params.add(img.isNull() ? null : img.longValue());
        }
        if (body.has("categoryId")) {
            setClauses.add("category_id = ?");
            JsonNode cat = body.get("categoryId");
            params.add(cat.isNull() ? null : cat.longValue());
        }
        if (body.has("price")) {
            setClauses.add("price = ?");
            params.add(body.get("price").decimalValue());
        }
        if (body.has("active")) {
            setClauses.add("active = ?");
            params.add(body.get("active").booleanValue());
        }

        if (!setClauses.isEmpty()) {
            params.add(id);
            jdbc.getJdbcTemplate().update(
                "UPDATE products SET " + String.join(", ", setClauses) + " WHERE id = ?",
                params.toArray()
            );
        }

        if (body.has("tags")) {
            JsonNode tagsNode = body.get("tags");
            List<String> tags = new ArrayList<>();
            if (tagsNode.isArray()) {
                tagsNode.forEach(n -> tags.add(n.asText()));
            }
            syncTags(id, tags);
        }
    }

    public long create(JsonNode body) {
        String name = body.has("name") ? body.get("name").toString() : "{\"en\":\"\"}";
        String barcode = body.has("barcode") ? body.get("barcode").asText() : java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        java.math.BigDecimal price = body.has("price") ? body.get("price").decimalValue() : java.math.BigDecimal.ZERO;
        boolean active = !body.has("active") || body.get("active").booleanValue();
        String description = body.has("description") ? body.get("description").toString() : null;
        Long categoryId = (body.has("categoryId") && !body.get("categoryId").isNull()) ? body.get("categoryId").longValue() : null;
        Long imageResourceId = (body.has("imageResourceId") && !body.get("imageResourceId").isNull()) ? body.get("imageResourceId").longValue() : null;

        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.getJdbcTemplate().update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                "INSERT INTO products (barcode, name, description, price, active, company_id, category_id, image_resource_id) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                new String[]{"id"});
            ps.setString(1, barcode);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setBigDecimal(4, price);
            ps.setBoolean(5, active);
            if (categoryId != null) ps.setLong(6, categoryId); else ps.setNull(6, java.sql.Types.BIGINT);
            if (imageResourceId != null) ps.setLong(7, imageResourceId); else ps.setNull(7, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<Product> findByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM products WHERE id IN (:ids)",
            Map.of("ids", ids), (rs, i) -> mapProduct(rs)
        );
    }

    public List<ProductSyncItem> resolveEffectiveCatalog(long companyId, Instant since) {
        return jdbc.query(
            "SELECT id, barcode, name, description, price, active, image_resource_id, updated_at " +
            "FROM products " +
            "WHERE company_id = :companyId " +
            "  AND (:since IS NULL OR updated_at > :since) " +
            "ORDER BY updated_at",
            Map.of("companyId", companyId, "since", since == null ? null : Timestamp.from(since)),
            (rs, i) -> mapSyncItem(rs)
        );
    }

    public void recordScanMiss(String barcode, long storeId) {
        jdbc.update(
            "INSERT INTO product_scan_misses (barcode, store_id) VALUES (:barcode, :storeId)",
            Map.of("barcode", barcode, "storeId", storeId)
        );
    }

    public record ProductPage(List<Product> products, boolean hasMore) {}

    public ProductPage searchByStore(long companyId, String q, int page, int size) {
        List<Product> results = jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM products " +
            "WHERE company_id = :companyId AND `public` = TRUE AND active = TRUE " +
            "  AND (LOWER(name->>'$.en') LIKE :q OR LOWER(name->>'$.ar') LIKE :q " +
            "       OR EXISTS (SELECT 1 FROM product_tags pt WHERE pt.product_id = products.id AND LOWER(pt.tag) LIKE :q)) " +
            "ORDER BY name->>'$.en' " +
            "LIMIT :pageLimit OFFSET :pageOffset",
            Map.of("companyId", companyId, "q", "%" + q.toLowerCase() + "%",
                   "pageLimit", size + 1, "pageOffset", page * size),
            (rs, i) -> mapProduct(rs)
        );
        boolean hasMore = results.size() > size;
        return new ProductPage(hasMore ? results.subList(0, size) : results, hasMore);
    }

    public ProductPage browseByStore(long companyId, Long categoryId, int page, int size) {
        String categoryFilter = categoryId != null ? " AND category_id = :categoryId" : "";
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("companyId", companyId);
        if (categoryId != null) params.put("categoryId", categoryId);
        params.put("pageLimit", size + 1);
        params.put("pageOffset", page * size);
        List<Product> results = jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM products " +
            "WHERE company_id = :companyId AND `public` = TRUE AND active = TRUE" + categoryFilter +
            " ORDER BY name->>'$.en' " +
            "LIMIT :pageLimit OFFSET :pageOffset",
            params,
            (rs, i) -> mapProduct(rs)
        );
        boolean hasMore = results.size() > size;
        return new ProductPage(hasMore ? results.subList(0, size) : results, hasMore);
    }
}
