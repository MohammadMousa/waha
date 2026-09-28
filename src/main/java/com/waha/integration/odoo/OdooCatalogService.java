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

    public int countVisibleProducts(long companyId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM products WHERE active = TRUE AND company_id = :companyId",
            Map.of("companyId", companyId), Integer.class);
    }

    // ── Category pull ─────────────────────────────────────────────────────────

    public int pullCategories() {
        ExternalSystem sys = requireSystem();
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
                upsertCategory(sys.id(), row);
                count++;
            } catch (Exception e) {
                log.warn("Skipping Odoo category id={}: {}", row.path("id").asLong(), e.getMessage());
            }
        }

        if (count > 0) systemRepo.updateLastCategorySyncAt(sys.id(), Instant.now());
        log.info("Odoo category pull: {} processed", count);
        return count;
    }

    private void upsertCategory(long systemId, JsonNode row) {
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
            long localId = insertCategory(nameJson, catKey);
            mappingRepo.save(systemId, "CATEGORY", String.valueOf(localId), String.valueOf(odooId), null);
        }
    }

    private long insertCategory(JsonNode name, String key) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name.toString());
        p.put("key",  key);
        jdbc.update(
            "INSERT INTO categories (name, `key`, public, active, sort_order, company_id) " +
            "VALUES (:name, :key, TRUE, TRUE, 0, 1)",
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

    public record FullPullResult(int added, int updated, int skipped, int total) {}

    // Pulled from product.product (variants): sale order lines reference variant IDs,
    // so mappings must hold variant IDs, never template IDs.
    private static final List<String> PRODUCT_FIELDS = List.of(
        "id", "name", "description_sale", "lst_price", "categ_id", "barcode",
        "active", "write_date", "image_512", "product_tmpl_id");

    public int pullProducts() {
        ExternalSystem sys = requireSystem();
        Instant since = sys.lastProductSyncAt();

        int total = 0;
        int offset = 0;
        final int PAGE = 100;

        while (true) {
            List<Object> fullDomain = buildProductDomain(since);

            List<JsonNode> rows = odooClient.searchRead(
                sys.baseUrl(), sys.apiKey(), sys.username(),
                "product.product", fullDomain, PRODUCT_FIELDS,
                PAGE, offset
            );

            if (rows.isEmpty()) break;

            for (JsonNode row : rows) {
                try {
                    upsertProduct(sys.id(), row);
                    total++;
                } catch (Exception e) {
                    log.warn("Skipping Odoo product id={}: {}", row.path("id").asLong(), e.getMessage());
                }
            }

            if (rows.size() < PAGE) break;
            offset += PAGE;
        }

        if (total > 0) systemRepo.updateLastProductSyncAt(sys.id(), Instant.now());
        log.info("Odoo product pull: {} processed", total);
        return total;
    }

    // Full pull — ignores lastProductSyncAt and fetches all products from Odoo.
    // Returns a breakdown of what changed so the caller can report it.
    public FullPullResult fullPullProducts() {
        ExternalSystem sys = requireSystem();
        int added = 0, updated = 0, skipped = 0;
        int offset = 0;
        final int PAGE = 100;

        while (true) {
            List<JsonNode> rows = odooClient.searchRead(
                sys.baseUrl(), sys.apiKey(), sys.username(),
                "product.product", List.of(), PRODUCT_FIELDS,
                PAGE, offset
            );

            if (rows.isEmpty()) break;

            for (JsonNode row : rows) {
                try {
                    boolean isNew = upsertProduct(sys.id(), row);
                    if (isNew) added++; else updated++;
                } catch (Exception e) {
                    log.warn("Skipping Odoo product id={} during full pull: {}", row.path("id").asLong(), e.getMessage());
                    skipped++;
                }
            }

            if (rows.size() < PAGE) break;
            offset += PAGE;
        }

        int total = added + updated + skipped;
        systemRepo.updateLastProductSyncAt(sys.id(), Instant.now());
        log.info("Odoo full product pull: added={}, updated={}, skipped={}, total={}", added, updated, skipped, total);
        return new FullPullResult(added, updated, skipped, total);
    }

    private boolean upsertProduct(long systemId, JsonNode row) throws Exception {
        long odooId     = row.path("id").asLong();
        long templateId = many2oneId(row.path("product_tmpl_id"));
        String enName   = row.path("name").asText("");
        double price    = row.path("lst_price").asDouble(0.0);
        boolean active  = row.path("active").asBoolean(true);

        // Placeholder stays keyed by template ID so products created by earlier template-based pulls keep it.
        String barcode = "ODOO_" + (templateId > 0 ? templateId : odooId);
        JsonNode bcNode = row.path("barcode");
        if (bcNode.isTextual() && !bcNode.asText().isBlank()) {
            barcode = bcNode.asText();
        }

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

        String imageBase64 = null;
        JsonNode imgNode = row.path("image_512");
        if (imgNode.isTextual() && !imgNode.asText().isEmpty()) {
            imageBase64 = imgNode.asText();
        }

        // Legacy mappings may hold template IDs, so a variant ID can collide with another product's
        // mapping. The barcode decides which local product this Odoo variant really is.
        Optional<ExternalMapping> existing = mappingRepo.findByExternalId(systemId, "PRODUCT", String.valueOf(odooId));
        Optional<Long> byBarcode = findLocalProductByBarcode(barcode);
        Long matchedId = null;
        if (existing.isPresent()) {
            long mappedId = Long.parseLong(existing.get().localId());
            if (byBarcode.isPresent() && byBarcode.get() != mappedId) {
                mappingRepo.deleteById(existing.get().id());
                matchedId = byBarcode.get();
                mappingRepo.save(systemId, "PRODUCT", String.valueOf(matchedId), String.valueOf(odooId), null);
            } else {
                matchedId = mappedId;
            }
        } else if (byBarcode.isPresent()) {
            matchedId = byBarcode.get();
            mappingRepo.save(systemId, "PRODUCT", String.valueOf(matchedId), String.valueOf(odooId), null);
        }

        long localId;
        boolean isNew;
        if (matchedId != null) {
            localId = matchedId;
            updateProduct(localId, nameJson, BigDecimal.valueOf(price), localCategoryId, active);
            // Keep primary barcode in product_barcodes in sync with Odoo's barcode.
            jdbc.getJdbcTemplate().update(
                "DELETE FROM product_barcodes WHERE product_id = ? AND is_primary = 1 AND barcode != ?",
                localId, barcode);
            jdbc.getJdbcTemplate().update(
                "INSERT IGNORE INTO product_barcodes (product_id, barcode, is_primary) VALUES (?, ?, 1)",
                localId, barcode);
            isNew = false;
        } else {
            localId = insertProduct(barcode, nameJson, BigDecimal.valueOf(price), localCategoryId, active);
            mappingRepo.save(systemId, "PRODUCT", String.valueOf(localId), String.valueOf(odooId), null);
            isNew = true;
        }
        if (imageBase64 != null) {
            storeProductImage(localId, odooId, imageBase64);
        }
        return isNew;
    }

    private Optional<Long> findLocalProductByBarcode(String barcode) {
        List<Long> ids = jdbc.getJdbcTemplate().query(
            "SELECT product_id FROM product_barcodes WHERE barcode = ? LIMIT 1",
            (rs, i) -> rs.getLong(1), barcode);
        return ids.stream().findFirst();
    }

    private static long many2oneId(JsonNode node) {
        if (node.isArray() && node.size() > 0) return node.get(0).asLong();
        if (node.isNumber()) return node.asLong();
        return 0;
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

    private long insertProduct(String barcode, String nameJson, BigDecimal price,
                               Long categoryId, boolean active) {
        Map<String, Object> p = new HashMap<>();
        p.put("barcode",    barcode);
        p.put("name",       nameJson);
        p.put("price",      price);
        p.put("categoryId", categoryId);
        p.put("active",     active);
        jdbc.update(
            "INSERT INTO products (barcode, name, description, price, active, public, company_id, category_id, updated_at) " +
            "VALUES (:barcode, :name, '{}', :price, :active, TRUE, 1, :categoryId, NOW())",
            p
        );
        long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        jdbc.getJdbcTemplate().update(
            "INSERT IGNORE INTO product_barcodes (product_id, barcode, is_primary) VALUES (?, ?, 1)",
            productId, barcode
        );
        return productId;
    }

    private void updateProduct(long id, String nameJson, BigDecimal price, Long categoryId, boolean active) {
        jdbc.update(
            "UPDATE products SET name = :name, price = :price, category_id = :categoryId, " +
            "active = :active, updated_at = NOW() WHERE id = :id",
            Map.of("id", id, "name", nameJson, "price", price, "categoryId", categoryId, "active", active)
        );
    }

    // ── Mapping repair ────────────────────────────────────────────────────────

    public record RepairChange(long localProductId, String fromOdooId, String toOdooId, String action) {}

    public record RepairResult(boolean dryRun, int valid, int remapped, int deadDeleted, int conflicts,
                               List<Long> orphanedProductIds, List<RepairChange> changes) {}

    // Re-derives every PRODUCT mapping from the local product's barcode and points it at the
    // matching Odoo variant (product.product). Also converts legacy template-ID mappings.
    // Mappings with no matching variant are deleted; clashes are reported, never forced.
    // dryRun=true computes the same result without writing anything.
    public RepairResult repairMappings(boolean dryRun) {
        ExternalSystem sys = requireSystem();
        List<ExternalMapping> all = mappingRepo.findAllByEntityType(sys.id(), "PRODUCT");

        Map<String, Long> mappingIdByOdooId = new HashMap<>();
        for (ExternalMapping m : all) mappingIdByOdooId.put(m.externalId(), m.id());

        int valid = 0, remapped = 0, deadDeleted = 0, conflicts = 0;
        List<Long> orphans = new ArrayList<>();
        List<RepairChange> changes = new ArrayList<>();
        final int BATCH = 50;

        for (int i = 0; i < all.size(); i += BATCH) {
            List<ExternalMapping> batch = all.subList(i, Math.min(i + BATCH, all.size()));

            Map<Long, String> barcodeByLocal = new HashMap<>();
            List<String> barcodes = new ArrayList<>();
            List<Long> templateIds = new ArrayList<>();
            for (ExternalMapping m : batch) {
                long localId = Long.parseLong(m.localId());
                String bc = findPrimaryBarcode(localId);
                if (bc == null) continue;
                barcodeByLocal.put(localId, bc);
                Long tmpl = placeholderTemplateId(bc);
                if (tmpl != null) templateIds.add(tmpl); else barcodes.add(bc);
            }

            Map<String, List<Long>> variantsByBarcode = new HashMap<>();
            if (!barcodes.isEmpty()) {
                for (JsonNode v : searchVariants(sys, List.of("barcode", "in", barcodes))) {
                    variantsByBarcode.computeIfAbsent(v.path("barcode").asText(), k -> new ArrayList<>())
                        .add(v.path("id").asLong());
                }
            }
            Map<Long, List<Long>> variantsByTemplate = new HashMap<>();
            if (!templateIds.isEmpty()) {
                for (JsonNode v : searchVariants(sys, List.of("product_tmpl_id", "in", templateIds))) {
                    variantsByTemplate.computeIfAbsent(many2oneId(v.path("product_tmpl_id")), k -> new ArrayList<>())
                        .add(v.path("id").asLong());
                }
            }

            for (ExternalMapping mapping : batch) {
                long localId = Long.parseLong(mapping.localId());
                String current = mapping.externalId();
                String bc = barcodeByLocal.get(localId);
                List<Long> candidates = List.of();
                if (bc != null) {
                    Long tmpl = placeholderTemplateId(bc);
                    candidates = tmpl != null
                        ? variantsByTemplate.getOrDefault(tmpl, List.of())
                        : variantsByBarcode.getOrDefault(bc, List.of());
                }

                if (candidates.isEmpty()) {
                    if (!dryRun) mappingRepo.deleteById(mapping.id());
                    mappingIdByOdooId.remove(current);
                    deadDeleted++;
                    orphans.add(localId);
                    changes.add(new RepairChange(localId, current, null, "DELETED"));
                    continue;
                }
                if (candidates.size() > 1) {
                    conflicts++;
                    changes.add(new RepairChange(localId, current, null, "AMBIGUOUS_SEVERAL_VARIANTS"));
                    continue;
                }

                String target = String.valueOf(candidates.get(0));
                if (target.equals(current)) {
                    valid++;
                    continue;
                }
                Long owner = mappingIdByOdooId.get(target);
                if (owner != null && owner != mapping.id()) {
                    conflicts++;
                    changes.add(new RepairChange(localId, current, target, "CONFLICT_TARGET_USED"));
                    continue;
                }
                if (!dryRun) mappingRepo.updateExternalId(mapping.id(), target);
                mappingIdByOdooId.remove(current);
                mappingIdByOdooId.put(target, mapping.id());
                remapped++;
                changes.add(new RepairChange(localId, current, target, "REMAPPED"));
            }
        }

        log.info("Odoo mapping repair{}: valid={}, remapped={}, deadDeleted={}, conflicts={}",
            dryRun ? " (dry run)" : "", valid, remapped, deadDeleted, conflicts);
        return new RepairResult(dryRun, valid, remapped, deadDeleted, conflicts, orphans, changes);
    }

    // Includes archived variants so an archived Odoo product is not mistaken for a deleted one.
    private List<JsonNode> searchVariants(ExternalSystem sys, List<Object> condition) {
        return odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(),
            "product.product",
            List.of(condition, List.of("active", "in", List.of(true, false))),
            List.of("id", "barcode", "product_tmpl_id"),
            1000, 0);
    }

    private static Long placeholderTemplateId(String barcode) {
        if (!barcode.startsWith("ODOO_")) return null;
        try {
            return Long.parseLong(barcode.substring(5));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String findPrimaryBarcode(long localProductId) {
        List<String> rows = jdbc.getJdbcTemplate().query(
            "SELECT barcode FROM product_barcodes WHERE product_id = ? AND is_primary = 1 LIMIT 1",
            (rs, i) -> rs.getString(1), localProductId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ExternalSystem requireSystem() {
        return systemRepo.findByName(SYSTEM_NAME)
            .filter(ExternalSystem::enabled)
            .orElseThrow(() -> new OdooException("Odoo integration is not configured or disabled"));
    }

    private List<Object> buildDomain(Instant since) {
        if (since == null) return List.of();
        return List.of(List.of("write_date", ">", ODOO_TS.format(since)));
    }

    private List<Object> buildProductDomain(Instant since) {
        List<Object> domain = new ArrayList<>();
        if (since != null) {
            // Name and price live on the template, so template edits must also count as changes.
            String ts = ODOO_TS.format(since);
            domain.add("|");
            domain.add(List.of("write_date", ">", ts));
            domain.add(List.of("product_tmpl_id.write_date", ">", ts));
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
