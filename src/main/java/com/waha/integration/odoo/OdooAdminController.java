package com.waha.integration.odoo;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.common.UnauthorizedException;
import com.waha.integration.ExternalSystem;
import com.waha.integration.ExternalSystemRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/odoo")
public class OdooAdminController {

    private final ExternalSystemRepository systemRepo;
    private final OdooCatalogService catalogService;
    private final OdooOrderSyncService orderSyncService;
    private final SessionService sessionService;

    public OdooAdminController(ExternalSystemRepository systemRepo,
                                OdooCatalogService catalogService,
                                OdooOrderSyncService orderSyncService,
                                SessionService sessionService) {
        this.systemRepo       = systemRepo;
        this.catalogService   = catalogService;
        this.orderSyncService = orderSyncService;
        this.sessionService   = sessionService;
    }

    // ── GET /api/admin/odoo/status ────────────────────────────────────────────
    @GetMapping("/status")
    public ResponseEntity<?> status(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long storeId) {
        try {
            sessionService.requirePermission(auth, Permission.MANAGE_STORES, storeId);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }

        Optional<ExternalSystem> sys = systemRepo.findByName("ODOO");
        if (sys.isEmpty()) {
            return ResponseEntity.ok(Map.of("configured", false));
        }
        ExternalSystem s = sys.get();
        Map<String, Integer> queueStats = systemRepo.queueStats(s.id());
        // Use HashMap — Map.of() rejects null values and lastCategorySyncAt/lastProductSyncAt
        // are null until the first pull runs.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("configured",          true);
        body.put("enabled",             s.enabled());
        body.put("baseUrl",             s.baseUrl()            != null ? s.baseUrl()            : "");
        body.put("username",            s.username()           != null ? s.username()           : "");
        body.put("customerOverride",    s.customerOverride()   != null ? s.customerOverride()   : "");
        body.put("lastCategorySyncAt",  s.lastCategorySyncAt() != null ? s.lastCategorySyncAt().toString() : null);
        body.put("lastProductSyncAt",   s.lastProductSyncAt()  != null ? s.lastProductSyncAt().toString()  : null);
        body.put("queue",               queueStats);
        return ResponseEntity.ok(body);
    }

    // ── POST /api/admin/odoo/configure ────────────────────────────────────────
    record ConfigureRequest(String baseUrl, String apiKey, String username, String customerOverride) {}

    @PostMapping("/configure")
    public ResponseEntity<?> configure(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long storeId,
            @RequestBody ConfigureRequest request) {
        try {
            sessionService.requirePermission(auth, Permission.MANAGE_STORES, storeId);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }

        if (request.baseUrl() == null || request.baseUrl().isBlank()) {
            return ResponseEntity.status(400).body(new ErrorResponse("baseUrl is required"));
        }

        String apiKey          = request.apiKey()          != null && !request.apiKey().isBlank()          ? request.apiKey().strip()          : null;
        String username        = request.username()        != null && !request.username().isBlank()        ? request.username().strip()        : null;
        String customerOverride = request.customerOverride() != null && !request.customerOverride().isBlank() ? request.customerOverride().strip() : null;
        ExternalSystem sys = systemRepo.upsert("ODOO", request.baseUrl().strip(), apiKey, username, customerOverride);
        // Reset cached partner so next order uses the new override.
        orderSyncService.resetPartnerCache();
        return ResponseEntity.ok(Map.of("id", sys.id(), "name", sys.name(), "enabled", sys.enabled()));
    }

    // ── POST /api/admin/odoo/pull/categories ──────────────────────────────────
    @PostMapping("/pull/categories")
    public ResponseEntity<?> pullCategories(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long storeId) {
        try {
            sessionService.requirePermission(auth, Permission.MANAGE_STORES, storeId);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }

        try {
            int count = catalogService.pullCategories(storeId);
            return ResponseEntity.ok(Map.of("pulled", count, "entityType", "CATEGORY"));
        } catch (OdooException e) {
            return ResponseEntity.status(502).body(new ErrorResponse("Odoo error: " + e.getMessage()));
        }
    }

    // ── POST /api/admin/odoo/pull/products ────────────────────────────────────
    @PostMapping("/pull/products")
    public ResponseEntity<?> pullProducts(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long storeId) {
        try {
            sessionService.requirePermission(auth, Permission.MANAGE_STORES, storeId);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }

        try {
            int pulled  = catalogService.pullProducts(storeId);
            int visible = catalogService.countVisibleProducts(storeId);
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("pulled",     pulled);
            body.put("visible",    visible);
            body.put("entityType", "PRODUCT");
            return ResponseEntity.ok(body);
        } catch (OdooException e) {
            return ResponseEntity.status(502).body(new ErrorResponse("Odoo error: " + e.getMessage()));
        }
    }

    // ── POST /api/admin/odoo/push/orders ──────────────────────────────────────
    @PostMapping("/push/orders")
    public ResponseEntity<?> pushOrders(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long storeId) {
        try {
            sessionService.requirePermission(auth, Permission.MANAGE_STORES, storeId);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }

        try {
            int pushed = orderSyncService.processPendingQueue();
            return ResponseEntity.ok(Map.of("pushed", pushed));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(new ErrorResponse("Odoo error: " + e.getMessage()));
        }
    }
}
