package com.waha.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.resource.ResourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Admin edit endpoint for products. Requires EDIT_PRODUCTS permission
// at the product's scope store (or root store if the product is global).
@RestController
@RequestMapping("/api/products")
public class ProductAdminController {

    private static final long ROOT_STORE_ID = 0L;

    private final ProductRepository productRepository;
    private final ResourceRepository resourceRepository;
    private final SessionService sessionService;

    public ProductAdminController(ProductRepository productRepository,
                                   ResourceRepository resourceRepository,
                                   SessionService sessionService) {
        this.productRepository = productRepository;
        this.resourceRepository = resourceRepository;
        this.sessionService = sessionService;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {

        Optional<Product> opt = productRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Product not found: " + id));
        }
        Product product = opt.get();

        long permStoreId = product.scopeStoreId() != null ? product.scopeStoreId() : ROOT_STORE_ID;
        sessionService.requirePermission(auth, Permission.EDIT_PRODUCTS, permStoreId);

        productRepository.patch(id, body);
        return ResponseEntity.ok().build();
    }

    // ── Gallery ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/images")
    public ResponseEntity<?> addImage(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody Map<String, Object> body) {
        Optional<Product> opt = productRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Product not found"));
        Product product = opt.get();
        long permStoreId = product.scopeStoreId() != null ? product.scopeStoreId() : ROOT_STORE_ID;
        sessionService.requirePermission(auth, Permission.EDIT_PRODUCTS, permStoreId);

        Object raw = body.get("resourceId");
        if (raw == null) return ResponseEntity.badRequest().body(new ErrorResponse("resourceId required"));
        long resourceId = ((Number) raw).longValue();
        resourceRepository.addGalleryImage(id, resourceId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/images/{resourceId}")
    public ResponseEntity<?> removeImage(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @PathVariable long resourceId) {
        Optional<Product> opt = productRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Product not found"));
        Product product = opt.get();
        long permStoreId = product.scopeStoreId() != null ? product.scopeStoreId() : ROOT_STORE_ID;
        sessionService.requirePermission(auth, Permission.EDIT_PRODUCTS, permStoreId);

        resourceRepository.removeGalleryImage(id, resourceId);
        return ResponseEntity.ok().build();
    }
}
