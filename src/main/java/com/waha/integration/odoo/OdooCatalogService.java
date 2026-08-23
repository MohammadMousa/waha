package com.waha.integration.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.integration.ExternalMapping;
import com.waha.integration.ExternalMappingRepository;
import com.waha.integration.ExternalSystem;
import com.waha.integration.ExternalSystemRepository;
import com.waha.resource.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class OdooCatalogService {

    private static final Logger log = LoggerFactory.getLogger(OdooCatalogService.class);
    private static final String SYSTEM_NAME = "ODOO";
    private static final DateTimeFormatter ODOO_TS = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final OdooClient odooClient;
    private final ExternalSystemRepository systemRepo;
    private final ExternalMappingRepository mappingRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ResourceRepository resourceRepo;

    public OdooCatalogService(OdooClient odooClient,
                               ExternalSystemRepository systemRepo,
                               ExternalMappingRepository mappingRepo,
                               NamedParameterJdbcTemplate jdbc,
                               ObjectMapper objectMapper,
                               ResourceRepository resourceRepo) {
        this.odooClient   = odooClient;
        this.systemRepo   = systemRepo;
        this.mappingRepo  = mappingRepo;
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
        this.resourceRepo = resourceRepo;
    }

    // Returns how many products are visible to this store via scope chain
    // (own + parent + root). Used to give meaningful feedback when pull returns 0
    // because the catalog is already up to date via inheritance.
    public int countVisibleProducts(Long storeId) {
        if (storeId == null) {
            return jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE active = TRUE",
                Map.of(), Integer.class);
        }
        // Build scope chain: walk parent_store_id up to root, plus 0 for system-wide.
        List<Long> chain = jdbc.query(
            "WITH RECURSIVE chain AS (" +
            "  SELECT id, parent_store_id FROM stores WHERE id = :id " +
            "  UNION ALL " +
            "  SELECT s.id, s.parent_store_id FROM stores s JOIN chain c ON s.id = c.parent_store_id" +
            ") SELECT id FROM chain",
            Map.of("id", storeId),
            (rs, i) -> rs.getLong("id"));
        if (chain.isEmpty()) return 0;
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM products WHERE active = TRUE AND scope_store_id IN (:chain)",
            Map.of("chain", chain), Integer.class);
    }

    // ── Category pull ─────────────────────────────────────────────────────────

    public int pullCategories(Long storeId) {
        ExternalSystem sys = requireSystem();
        // Always write to root so all children inherit the catalog.
        Long rootStoreId = findRootStoreId(storeId);
        List<Object> domain = buildDomain(sys.lastCategorySyncAt());
        List<JsonNode> rows = odooClient.searchRead(
            sys.baseUrl(), sys.apiKey(), sys.username(),
            "product.category", domain,
            List.of("id", "name", "complete_name", "parent_id", "write_date"),
            200, 0
        );

        int count = 0;
        for (JsonNode row : rows) {
            try {
                upsertCategory(sys.id(), row, rootStoreId);
                count++;
            } catch (Exception e) {
                log.warn("Skipping Odoo category id={}: {}", row.path("id").asLong(), e.getMessage());
            }
        }

        if (count > 0) systemRepo.updateLastCategorySyncAt(sys.id(), Instant.now());
        log.info("Odoo category pull: {} processed, rootStoreId={}", count, rootStoreId);
        return count;
    }

    private void upsertCategory(long systemId, JsonNode row, Long storeId) {
        long odooId   = row.path("id").asLong();
        String name   = row.path("name").asText();
        String catKey = slugify(name) + "_" + odooId;

        JsonNode nameJson;
        try {
            nameJson = objectMapper.readTree("{\"en\":\"" + escapeJson(name) + "\",\"ar\":\"" + escapeJson(name) + "\"}");
        } catch (Exception e) {
            throw new OdooException("Failed to build name JSON for category " + odooId);
        }

        Optional<ExternalMapping> existing = mappingRepo.findByExternalId(systemId, "CATEGORY", String.valueOf(odooId));
        if (existing.isPresent()) {
            long localId = Long.parseLong(existing.get().localId());
            updateCategory(localId, nameJson, catKey);
        } else {
            long localId = insertCategory(storeId, nameJson, catKey);
            mappingRepo.save(systemId, "CATEGORY", String.valueOf(localId), String.valueOf(odooId), storeId);
        }
    }

    private long insertCategory(Long storeId, JsonNode name, String key) {
        Map<String, Object> p = new HashMap<>();
        p.put("storeId", storeId);
        p.put("name",    name.toString());
        p.put("key",     key);
        jdbc.update(
            "INSERT INTO categories (scope_store_id, name, `key`, public, active, sort_order) " +
            "VALUES (:storeId, :name, :key, TRUE, TRUE, 0)",
            p
        );
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
    }

    private void updateCategory(long id, JsonNode name, String key) {
        jdbc.update(
            "UPDATE categories SET name = :name, `key` = :key WHERE id = :id",
            Map.of("id", id, "name", name.toString(), "key", key)
        );
    }

    // ── Product pull ──────────────────────────────────────────────────────────

    public int pullProducts(Long storeId) {
        ExternalSystem sys = requireSystem();
        // Always write to root so all children inherit the catalog.
        Long rootStoreId = findRootStoreId(storeId);
        Instant since = sys.lastProductSyncAt();

        // Look up scope_store_type once — required by the products CHECK constraint.
        String storeType = rootStoreId != null
            ? jdbc.queryForObject("SELECT store_type FROM stores WHERE id = :id",
                Map.of("id", rootStoreId), String.class)
            : null;

        int total = 0;
        int offset = 0;
        final int PAGE = 100;

        while (true) {
            List<Object> fullDomain = buildProductDomain(since);

            List<JsonNode> rows = odooClient.searchRead(
                sys.baseUrl(), sys.apiKey(), sys.username(),
                "product.template", fullDomain,
                List.of("id", "name", "description_sale", "list_price",
                        "categ_id", "barcode", "active", "write_date", "image_512"),
                PAGE, offset
            );

            if (rows.isEmpty()) break;

            for (JsonNode row : rows) {
                try {
                    upsertProduct(sys.id(), row, rootStoreId, storeType);
                    total++;
                } catch (Exception e) {
                    log.warn("Skipping Odoo product id={}: {}", row.path("id").asLong(), e.getMessage());
                }
            }

            if (rows.size() < PAGE) break;
            offset += PAGE;
        }

        if (total > 0) systemRepo.updateLastProductSyncAt(sys.id(), Instant.now());
        log.info("Odoo product pull: {} processed, rootStoreId={}", total, rootStoreId);
        return total;
    }

    private void upsertProduct(long systemId, JsonNode row, Long storeId, String storeType) throws Exception {
        long odooId    = row.path("id").asLong();
        String enName  = row.path("name").asText("");
        double price   = row.path("list_price").asDouble(0.0);
        boolean active = row.path("active").asBoolean(true);

        // Barcode: Odoo returns false/boolean when not set — fall back to ODOO_{id}.
        String barcode = "ODOO_" + odooId;
        JsonNode bcNode = row.path("barcode");
        if (bcNode.isTextual() && !bcNode.asText().isBlank()) {
            barcode = bcNode.asText();
        }

        // categ_id: JSON-RPC returns [odooId,"Name"], XML-RPC parser returns the first
        // <int> it finds inside the member → stored as a numeric node, not an array.
        Long localCategoryId = null;
        JsonNode categNode = row.path("categ_id");
        long odooCatId = 0;
        if (categNode.isArray() && categNode.size() > 0) {
            odooCatId = categNode.get(0).asLong();
        } else if (categNode.isNumber()) {
            odooCatId = categNode.asLong();
        }
        if (odooCatId > 0) {
            Optional<ExternalMapping> catMap = mappingRepo.findByExternalId(systemId, "CATEGORY", String.valueOf(odooCatId));
            localCategoryId = catMap.map(m -> Long.parseLong(m.localId())).orElse(null);
        }

        String nameJson = "{\"en\":\"" + escapeJson(enName) + "\",\"ar\":\"" + escapeJson(enName) + "\"}";

        // image_512: base64 string when Odoo product has an image, boolean false otherwise.
        String imageBase64 = null;
        JsonNode imgNode = row.path("image_512");
        if (imgNode.isTextual() && !imgNode.asText().isEmpty()) {
            imageBase64 = imgNode.asText();
        }

        Optional<ExternalMapping> existing = mappingRepo.findByExternalId(systemId, "PRODUCT", String.valueOf(odooId));
        long localId;
        if (existing.isPresent()) {
            localId = Long.parseLong(existing.get().localId());
            updateProduct(localId, nameJson, BigDecimal.valueOf(price), localCategoryId, active);
        } else {
            localId = insertProduct(storeId, storeType, barcode, nameJson, BigDecimal.valueOf(price), localCategoryId, active);
            mappingRepo.save(systemId, "PRODUCT", String.valueOf(localId), String.valueOf(odooId), storeId);
        }
        if (imageBase64 != null) {
            storeProductImage(localId, odooId, imageBase64);
        }
    }

    private void storeProductImage(long productId, long odooId, String base64) {
        try {
            byte[] bytes = java.util.Base64.getMimeDecoder().decode(base64);
            String sha256 = sha256hex(bytes);
            long resId = resourceRepo.findIdBySha256(sha256)
                .orElseGet(() -> resourceRepo.store(
                    "odoo_product_" + odooId + ".png", "image/png", bytes.length, sha256, bytes));
            jdbc.update("UPDATE products SET image_resource_id = :resId WHERE id = :id",
                Map.of("resId", resId, "id", productId));
        } catch (Exception e) {
            log.warn("Failed to store image for Odoo product id={}: {}", odooId, e.getMessage());
        }
    }

    private static String sha256hex(byte[] data) throws java.security.NoSuchAlgorithmException {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private long insertProduct(Long storeId, String storeType, String barcode, String nameJson,
                                BigDecimal price, Long categoryId, boolean active) {
        Map<String, Object> p = new HashMap<>();
        p.put("storeId",    storeId);
        p.put("storeType",  storeType);
        p.put("barcode",    barcode);
        p.put("name",       nameJson);
        p.put("price",      price);
        p.put("categoryId", categoryId);
        p.put("active",     active);
        jdbc.update(
            "INSERT INTO products (scope_store_id, scope_store_type, barcode, name, description, price, active, public, category_id, updated_at) " +
            "VALUES (:storeId, :storeType, :barcode, :name, '{}', :price, :active, TRUE, :categoryId, NOW())",
            p
        );
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
    }

    private void updateProduct(long id, String nameJson, BigDecimal price, Long categoryId, boolean active) {
        jdbc.update(
            "UPDATE products SET name = :name, price = :price, category_id = :categoryId, " +
            "active = :active, updated_at = NOW() WHERE id = :id",
            Map.of("id", id, "name", nameJson, "price", price, "categoryId", categoryId, "active", active)
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Walks parent_store_id up to the root (the store with no parent).
    // Returns null if storeId is null (system-wide scope).
    // Catalog pulls always write to root so all children inherit the products.
    private Long findRootStoreId(Long storeId) {
        if (storeId == null) return null;
        List<Long> roots = jdbc.query(
            "WITH RECURSIVE chain AS (" +
            "  SELECT id, parent_store_id FROM stores WHERE id = :id " +
            "  UNION ALL " +
            "  SELECT s.id, s.parent_store_id FROM stores s JOIN chain c ON s.id = c.parent_store_id" +
            ") SELECT id FROM chain WHERE parent_store_id IS NULL LIMIT 1",
            Map.of("id", storeId),
            (rs, i) -> rs.getLong("id"));
        return roots.isEmpty() ? storeId : roots.get(0);
    }

    private ExternalSystem requireSystem() {
        return systemRepo.findByName(SYSTEM_NAME)
            .filter(ExternalSystem::enabled)
            .orElseThrow(() -> new OdooException("Odoo integration is not configured or disabled"));
    }

    // Incremental domain: if since != null, only fetch records changed after it.
    private List<Object> buildDomain(Instant since) {
        if (since == null) return List.of();
        return List.of(List.of("write_date", ">", ODOO_TS.format(since)));
    }

    private List<Object> buildProductDomain(Instant since) {
        List<Object> domain = new ArrayList<>();
        if (since != null) {
            domain.add(List.of("write_date", ">", ODOO_TS.format(since)));
        }
        return domain;
    }

    private static String slugify(String input) {
        if (input == null) return "category";
        return input.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_|_$", "");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
