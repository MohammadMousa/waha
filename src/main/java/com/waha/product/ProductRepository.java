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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Scope resolution happens in a single indexed query per method, ranked by
// specificity across the requesting store's FULL ancestor chain (arbitrary
// depth - see StoreRepository.resolveScopeChain), not a fixed two-level
// check. Still no per-tier round trips: the chain is resolved once, then
// used to build one query with a dynamically-sized ranking, not N
// sequential "try this level" queries.
@Repository
public class ProductRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private Product mapProduct(ResultSet rs) throws SQLException {
        long scope = rs.getLong("scope_store_id");
        Long scopeStoreId = rs.wasNull() ? null : scope;
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
            scopeStoreId,
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

    // Builds "CASE scope_store_id WHEN :s0 THEN 0 WHEN :s1 THEN 1 ... ELSE N
    // END" - a dynamically-sized ranking, one WHEN per entry in scopeChain
    // (index 0 = the requesting store's own id = highest priority, last
    // index = the tree root = lowest). Rows with scope_store_id NULL
    // (GLOBAL) never match any WHEN (NULL never equals anything via `=`),
    // so they always fall to ELSE - lowest priority, exactly as intended.
    // Only loop-generated parameter NAMES and the loop index go into the
    // SQL text directly; every actual VALUE is bound, so this is not
    // string-built in any way that risks injection.
    private static String buildSpecificityCase(List<Long> scopeChain, Map<String, Object> params, String column) {
        StringBuilder sql = new StringBuilder("CASE ").append(column).append(' ');
        for (int i = 0; i < scopeChain.size(); i++) {
            String key = "rank" + i;
            sql.append("WHEN :").append(key).append(" THEN ").append(i).append(' ');
            params.put(key, scopeChain.get(i));
        }
        sql.append("ELSE ").append(scopeChain.size()).append(" END");
        return sql.toString();
    }

    private static final String PRODUCT_COLS =
        "id, barcode, name, description, price, active, scope_store_id, `public`, category_id, image_resource_id";

    // The scan endpoint. scopeChain is this store's own id + its full
    // ancestor chain, most specific first (see
    // StoreRepository.resolveScopeChain) - which tier resolves for this
    // barcode depends on how far up that chain a matching row exists. An
    // inactive override still wins here (returned with active=false) - a
    // store (at any level) having an inactive override for a barcode means
    // it opted out entirely, it does not fall through to a broader tier.
    public Optional<Product> resolveByBarcode(String barcode, List<Long> scopeChain) {
        Map<String, Object> params = new HashMap<>();
        params.put("barcode", barcode);
        params.put("scopeIds", scopeChain);
        String rankCase = buildSpecificityCase(scopeChain, params, "scope_store_id");

        List<Product> results = jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM products " +
            "WHERE barcode = :barcode AND (scope_store_id IN (:scopeIds) OR scope_store_id IS NULL) " +
            "ORDER BY " + rankCase + " LIMIT 1",
            params, (rs, i) -> mapProduct(rs)
        );
        return results.stream().findFirst();
    }

    public java.util.Optional<Product> findById(long id) {
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
            // NamedParameterJdbcTemplate wraps JdbcTemplate — use getJdbcTemplate() for plain ?-style.
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
                "INSERT INTO products (barcode, name, description, price, active, category_id, image_resource_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
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

    // Used by OrderService to price order items server-side - the client
    // sends productId + quantity only, never a price.
    public List<Product> findByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM products WHERE id IN (:ids)",
            Map.of("ids", ids), (rs, i) -> mapProduct(rs)
        );
    }

    // Delta sync for a store's offline catalog cache. Same specificity
    // ranking as resolveByBarcode, but bulk: ROW_NUMBER() picks exactly one
    // winning row per barcode (rn=1), then the since-filter is applied
    // AFTER ranking, not before. That ordering matters - if a broader tier
    // changes but a more specific override still wins for this store, the
    // store's effective view hasn't actually changed and shouldn't show up
    // in the delta, even though some row for that barcode was touched.
    public List<ProductSyncItem> resolveEffectiveCatalog(List<Long> scopeChain, Instant since) {
        Map<String, Object> params = new HashMap<>();
        params.put("scopeIds", scopeChain);
        params.put("since", since == null ? null : Timestamp.from(since));
        String rankCase = buildSpecificityCase(scopeChain, params, "scope_store_id");

        return jdbc.query(
            "SELECT id, barcode, name, description, price, active, image_resource_id, updated_at FROM ( " +
            "  SELECT id, barcode, name, description, price, active, image_resource_id, updated_at, " +
            "         ROW_NUMBER() OVER (PARTITION BY barcode ORDER BY " + rankCase + ") AS rn " +
            "  FROM products " +
            "  WHERE (scope_store_id IN (:scopeIds) OR scope_store_id IS NULL) " +
            ") ranked " +
            "WHERE rn = 1 " +
            "  AND (:since IS NULL OR updated_at > :since) " +
            "ORDER BY updated_at",
            params, (rs, i) -> mapSyncItem(rs)
        );
    }

    // Fire-and-forget log of every unrecognized-barcode scan.
    public void recordScanMiss(String barcode, long storeId) {
        jdbc.update(
            "INSERT INTO product_scan_misses (barcode, store_id) VALUES (:barcode, :storeId)",
            Map.of("barcode", barcode, "storeId", storeId)
        );
    }

    public record ProductPage(List<Product> products, boolean hasMore) {}

    // Full-text name search across both language fields. Same scope/ranking
    // as browseByStore — most specific store override wins per barcode.
    // LOWER + LIKE is good enough for a small catalog; upgrade to FULLTEXT
    // index when product counts grow past ~10k.
    public ProductPage searchByStore(List<Long> scopeChain, String q, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        params.put("scopeIds", scopeChain);
        String rankCase = buildSpecificityCase(scopeChain, params, "scope_store_id");
        params.put("pageLimit", size + 1);
        params.put("pageOffset", page * size);
        params.put("q", "%" + q.toLowerCase() + "%");

        List<Product> results = jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM ( " +
            "  SELECT " + PRODUCT_COLS + ", " +
            "         ROW_NUMBER() OVER (PARTITION BY barcode ORDER BY " + rankCase + ") AS rn " +
            "  FROM products " +
            "  WHERE (scope_store_id IN (:scopeIds) OR scope_store_id IS NULL) " +
            "    AND `public` = TRUE " +
            "    AND (LOWER(name->>'$.en') LIKE :q OR LOWER(name->>'$.ar') LIKE :q " +
            "         OR EXISTS (SELECT 1 FROM product_tags pt WHERE pt.product_id = products.id AND LOWER(pt.tag) LIKE :q)) " +
            ") ranked " +
            "WHERE rn = 1 AND active = TRUE " +
            "ORDER BY name->>'$.en' " +
            "LIMIT :pageLimit OFFSET :pageOffset",
            params, (rs, i) -> mapProduct(rs)
        );
        boolean hasMore = results.size() > size;
        return new ProductPage(hasMore ? results.subList(0, size) : results, hasMore);
    }

    // Discovery/browsing. public filter applied before ranking (scope-independent,
    // safe to filter early). active filter applied after ranking (scope-dependent).
    // ORDER BY the English name extracted from the JSON — consistent sort regardless
    // of whether the JSON has both languages or just one.
    public ProductPage browseByStore(List<Long> scopeChain, Long categoryId, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        params.put("scopeIds", scopeChain);
        String rankCase = buildSpecificityCase(scopeChain, params, "scope_store_id");
        params.put("pageLimit", size + 1);
        params.put("pageOffset", page * size);
        params.put("categoryId", categoryId);

        String categoryFilter = categoryId != null ? " AND category_id = :categoryId" : "";

        List<Product> results = jdbc.query(
            "SELECT " + PRODUCT_COLS + " FROM ( " +
            "  SELECT " + PRODUCT_COLS + ", " +
            "         ROW_NUMBER() OVER (PARTITION BY barcode ORDER BY " + rankCase + ") AS rn " +
            "  FROM products " +
            "  WHERE (scope_store_id IN (:scopeIds) OR scope_store_id IS NULL) " +
            "    AND `public` = TRUE" + categoryFilter +
            ") ranked " +
            "WHERE rn = 1 AND active = TRUE " +
            "ORDER BY name->>'$.en' " +
            "LIMIT :pageLimit OFFSET :pageOffset",
            params, (rs, i) -> mapProduct(rs)
        );

        boolean hasMore = results.size() > size;
        List<Product> pageResults = hasMore ? results.subList(0, size) : results;
        return new ProductPage(pageResults, hasMore);
    }
}
