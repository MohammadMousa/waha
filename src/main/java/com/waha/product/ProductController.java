package com.waha.product;

import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.product.dto.ProductListResponse;
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

    public ProductController(ProductRepository productRepository, StoreRepository storeRepository,
                              SessionService sessionService, ResourceRepository resourceRepository) {
        this.productRepository = productRepository;
        this.storeRepository = storeRepository;
        this.sessionService = sessionService;
        this.resourceRepository = resourceRepository;
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
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }

        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<Long> scopeChain = storeRepository.resolveScopeChain(resolvedStoreId);
        var result = productRepository.browseByStore(scopeChain, categoryId, page, cappedSize);
        return ResponseEntity.ok(new ProductListResponse(result.products(), page, cappedSize, result.hasMore()));
    }

    // Product detail page - tapping an item in the browse list above. Not
    // store-scoped on purpose: the id came from a browse call that was
    // already store-scoped, so re-resolving here would be redundant, and a
    // direct/bookmarked link to a product shouldn't need a storeId to
    // render (same "don't gatekeep on something already established"
    // principle as removing the checkout-time scope check).
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable long id) {
        Optional<Product> product = productRepository.findByIds(List.of(id)).stream().findFirst();
        if (product.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Product " + id + " not found"));
        // Include gallery image resource IDs and tags inline so the client avoids extra round-trips.
        List<Long> galleryIds = resourceRepository.findGalleryByProduct(id)
            .stream().map(ResourceRepository.GalleryItem::resourceId).toList();
        List<String> tags = productRepository.findTagsByProduct(id);
        Map<String, Object> resp = new HashMap<>();
        resp.put("id",              product.get().id());
        resp.put("barcode",         product.get().barcode());
        resp.put("name",            product.get().name());
        resp.put("description",     product.get().description());
        resp.put("price",           product.get().price());
        resp.put("active",          product.get().active());
        resp.put("scopeStoreId",    product.get().scopeStoreId());
        resp.put("publicListed",    product.get().publicListed());
        resp.put("categoryId",      product.get().categoryId());
        resp.put("imageResourceId", product.get().imageResourceId());
        resp.put("imageResourceIds", galleryIds);
        resp.put("tags",            tags);
        return ResponseEntity.ok(resp);
    }

    // What the kiosk scanner calls on every scan. storeId now follows the
    // same rule as browse above: explicit always wins, otherwise resolved
    // from the session. Kiosk was originally built as a separate
    // device-identity concept with no session at all - that assumption was
    // wrong (kiosk uses a real logged-in account, per an operator's
    // one-time device setup, just restricted to kiosk UI by the frontend),
    // so this endpoint shouldn't be the one place that can never benefit
    // from a session once that setup exists. Resolves different prices for
    // the same barcode depending on which store is asking, walking that
    // store's full ancestor chain (arbitrary depth, most specific wins -
    // see StoreRepository.resolveScopeChain / ProductRepository).
    // Deliberately does NOT check inventory quantity - a scanned barcode
    // plus a physically-held item is sufficient evidence the product can
    // be sold (product doc, section 4-6).
    @GetMapping("/barcode/{barcode}")
    public ResponseEntity<?> getByBarcode(@PathVariable String barcode,
                                           @RequestParam(required = false) Long storeId,
                                           @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }

        List<Long> scopeChain = storeRepository.resolveScopeChain(resolvedStoreId);
        Optional<Product> product = productRepository.resolveByBarcode(barcode, scopeChain);

        if (product.isEmpty()) {
            productRepository.recordScanMiss(barcode, resolvedStoreId);
            return ResponseEntity.status(404).body(new ErrorResponse("No product found for barcode " + barcode));
        }
        if (!product.get().active()) {
            return ResponseEntity.status(409).body(new ErrorResponse("Product is not currently sellable"));
        }
        return ResponseEntity.ok(product.get());
    }

    // Name search. Same scope / session-resolution rules as browse above.
    // q must be non-empty; storeId or a logged-in session is required.
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q,
                                     @RequestParam(required = false) Long storeId,
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
        List<Long> scopeChain = storeRepository.resolveScopeChain(resolvedStoreId);
        var result = productRepository.searchByStore(scopeChain, q.trim(), page, cappedSize);
        return ResponseEntity.ok(new ProductListResponse(result.products(), page, cappedSize, result.hasMore()));
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

        List<Long> scopeChain = storeRepository.resolveScopeChain(resolvedStoreId);
        Instant syncedAt = Instant.now();
        return ResponseEntity.ok(new ProductSyncResponse(syncedAt, productRepository.resolveEffectiveCatalog(scopeChain, since)));
    }
}
