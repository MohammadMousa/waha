package com.waha.product;

import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.pricing.VatPricing;
import com.waha.product.dto.ProductListResponse;
import com.waha.product.dto.ProductView;
import com.waha.product.dto.ProductSyncResponse;
import com.waha.resource.ResourceRepository;
import com.waha.store.StoreRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final ProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final SessionService sessionService;
    private final ResourceRepository resourceRepository;
    private final VatPricing vatPricing;
    private final MissingProductTracker missTracker;
    private final com.waha.config.ConfigService configService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public ProductController(ProductRepository productRepository, StoreRepository storeRepository,
                              SessionService sessionService, ResourceRepository resourceRepository,
                              VatPricing vatPricing, MissingProductTracker missTracker,
                              com.waha.config.ConfigService configService,
                              com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.mapper = mapper;
        this.productRepository = productRepository;
        this.storeRepository = storeRepository;
        this.sessionService = sessionService;
        this.resourceRepository = resourceRepository;
        this.vatPricing = vatPricing;
        this.missTracker = missTracker;
        this.configService = configService;
    }

    // Discovery/browsing. storeId is optional - an explicit value still
    // wins if sent, but once logged in with a store selected via POST
    // /api/auth/store, it's resolved from the session instead (see
    // SessionService.resolveStoreId). A guest with no session and no
    // explicit storeId gets a clean 400, not a crash. Active-only listing
    // (no "physically scanned it, so show the 409 anyway" context to
    // justify surfacing inactive items here, unlike the barcode endpoint
    // below).
    @GetMapping
    public ResponseEntity<?> browse(@RequestParam(required = false) Long storeId,
                                     @RequestHeader(value = "Authorization", required = false) String authHeader,
                                     @RequestParam(required = false) Long categoryId,
                                     @RequestParam(required = false) String full,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }

        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long companyId = storeRepository.findCompanyId(resolvedStoreId);
        var result = productRepository.browseByStore(companyId, categoryId, page, cappedSize);
        return ResponseEntity.ok(listBody(result.products(), resolvedStoreId, companyId, page, cappedSize, result.hasMore(), full));
    }

    // Product detail page - tapping an item in the browse list above. Not
    // store-scoped on purpose: the id came from a browse call that was
    // already store-scoped, so re-resolving here would be redundant, and a
    // direct/bookmarked link to a product shouldn't need a storeId to
    // render (same "don't gatekeep on something already established"
    // principle as removing the checkout-time scope check).
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable long id, @RequestParam(required = false) String full) {
        Optional<Product> product = productRepository.findByIds(List.of(id)).stream().findFirst();
        if (product.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Product " + id + " not found"));
        // Include gallery image resource IDs, tags, and all barcodes inline so the client avoids extra round-trips.
        List<Long> galleryIds = resourceRepository.findGalleryByProduct(id)
            .stream().map(ResourceRepository.GalleryItem::resourceId).toList();
        List<String> tags = productRepository.findTagsByProduct(id);
        List<String> barcodes = productRepository.findBarcodesByProduct(id)
            .stream().map(ProductRepository.BarcodeEntry::barcode).toList();
        Map<String, Object> resp = new HashMap<>();
        resp.put("id",              product.get().id());
        resp.put("barcode",         product.get().barcode());
        resp.put("barcodes",        barcodes);
        resp.put("name",            product.get().name());
        resp.put("description",     product.get().description());
        // price stays the stored price without VAT (the edit form saves it back); priceWithVat is for display.
        resp.put("price",           product.get().price());
        resp.put("priceWithVat",    VatPricing.withVat(product.get().price(), vatPricing.organizationVatRate(product.get().companyId())));
        resp.put("active",          product.get().active());
        resp.put("companyId",       product.get().companyId());
        resp.put("publicListed",    product.get().publicListed());
        resp.put("categoryId",      product.get().categoryId());
        resp.put("imageResourceId", product.get().imageResourceId());
        resp.put("imageResourceIds", galleryIds);
        resp.put("tags",            tags);
        if (isFull(full)) {
            var extra = productRepository.findExtras(List.of(id)).get(id);
            resp.put("priceExVat",  product.get().price());
            resp.put("sku",         extra == null ? null : extra.sku());
            resp.put("updatedAt",   extra == null ? null : extra.updatedAt());
            resp.put("barcodeList", productRepository.findBarcodesByProduct(id).stream().map(ProductController::barcodeJson).toList());
        }
        return ResponseEntity.ok(resp);
    }

    // Kiosk scanner endpoint. storeId: explicit wins, else resolved from session.
    @GetMapping("/barcode/{barcode}")
    public ResponseEntity<?> getByBarcode(@PathVariable String barcode,
                                           @RequestParam(required = false) Long storeId,
                                           @RequestParam(required = false) String full,
                                           @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }

        long companyId = storeRepository.findCompanyId(resolvedStoreId);
        Optional<Product> product = productRepository.resolveByBarcode(barcode, companyId);

        if (product.isEmpty()) {
            missTracker.record(resolvedStoreId, barcode, () -> sessionService.resolveUsername(null, authHeader));
            return ResponseEntity.status(404).body(new ErrorResponse("No product found for barcode " + barcode));
        }
        if (!product.get().active() && !configService.boolProperty(companyId, "scan_disabled_products", false)) {
            return ResponseEntity.status(409).body(new ErrorResponse("Product is not currently sellable"));
        }
        if (isFull(full)) return ResponseEntity.ok(fullViews(List.of(product.get()), resolvedStoreId, companyId).get(0));
        return ResponseEntity.ok(views(List.of(product.get()), resolvedStoreId, companyId).get(0));
    }

    // Name search. Same scope / session-resolution rules as browse above.
    // q must be non-empty; storeId or a logged-in session is required.
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q,
                                     @RequestParam(required = false) Long storeId,
                                     @RequestParam(required = false) String full,
                                     @RequestHeader(value = "Authorization", required = false) String authHeader,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.status(400).body(new ErrorResponse("q is required"));
        }
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long companyId = storeRepository.findCompanyId(resolvedStoreId);
        var result = productRepository.searchByStore(companyId, q.trim(), page, cappedSize);
        return ResponseEntity.ok(listBody(result.products(), resolvedStoreId, companyId, page, cappedSize, result.hasMore(), full));
    }

    // Delta sync for an offline local catalog cache, scoped to one store's
    // effective catalog (not the whole system). Omit `since` for a full
    // sync (first run on a device); pass the previous response's `syncedAt`
    // on every call after that. Same session-resolution rule as everywhere
    // else now - see the note on the barcode endpoint above for why this
    // was wrong to special-case as sessionless.
    @GetMapping("/sync")
    public ResponseEntity<?> sync(@RequestParam(required = false) Long storeId,
                                   @RequestHeader(value = "Authorization", required = false) String authHeader,
                                   @RequestParam(required = false) Instant since) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }

        long companyId = storeRepository.findCompanyId(resolvedStoreId);
        Instant syncedAt = Instant.now();
        var rate = storeRepository.getStoreConfig(resolvedStoreId).vatRate();
        boolean withVat = vatPricing.showWithVat(companyId);
        var items = productRepository.resolveEffectiveCatalog(companyId, since).stream().map(i -> {
            var gross = VatPricing.withVat(i.price(), rate);
            return new com.waha.product.dto.ProductSyncItem(i.id(), i.barcode(), i.name(), i.description(),
                withVat ? gross : i.price(), i.active(), i.imageResourceId(), i.updatedAt(), gross);
        }).toList();
        return ResponseEntity.ok(new ProductSyncResponse(syncedAt, items));
    }

    private static boolean isFull(String full) {
        return "1".equals(full) || "true".equalsIgnoreCase(full);
    }

    private static Map<String, Object> barcodeJson(ProductRepository.BarcodeEntry b) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", b.id());
        m.put("barcode", b.barcode());
        m.put("isPrimary", b.isPrimary());
        return m;
    }

    private Object listBody(List<Product> products, long storeId, long companyId, int page, int size, boolean hasMore, String full) {
        if (!isFull(full)) return new ProductListResponse(views(products, storeId, companyId), page, size, hasMore);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("products", fullViews(products, storeId, companyId));
        body.put("page", page);
        body.put("size", size);
        body.put("hasMore", hasMore);
        return body;
    }

    // Opt-in (?full=1) product answers for a kiosk that keeps a local copy: the normal fields plus the price
    // without VAT, sku, updatedAt and every barcode. Without the flag nothing changes.
    private List<Map<String, Object>> fullViews(List<Product> products, long storeId, long companyId) {
        var ids = products.stream().map(Product::id).toList();
        var extras = productRepository.findExtras(ids);
        var barcodes = productRepository.findBarcodesByProducts(ids);
        var views = views(products, storeId, companyId);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            Map<String, Object> m = mapper.convertValue(views.get(i), new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {});
            var extra = extras.get(p.id());
            m.put("priceExVat", p.price());
            m.put("sku", extra == null ? null : extra.sku());
            m.put("updatedAt", extra == null ? null : extra.updatedAt());
            m.put("barcodeList", barcodes.getOrDefault(p.id(), List.of()).stream().map(ProductController::barcodeJson).toList());
            out.add(m);
        }
        return out;
    }

    // Customer-facing price fields: with VAT unless the organization turned that off (prices_exclude_vat).
    private List<ProductView> views(List<Product> products, long storeId, long companyId) {
        var rate = storeRepository.getStoreConfig(storeId).vatRate();
        boolean withVat = vatPricing.showWithVat(companyId);
        return products.stream().map(p -> ProductView.of(p, VatPricing.withVat(p.price(), rate), withVat)).toList();
    }
}
