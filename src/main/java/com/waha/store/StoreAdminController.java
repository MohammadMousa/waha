package com.waha.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// Admin edit endpoint for stores. Requires MANAGE_STORES permission.
@RestController
@RequestMapping("/api/stores")
public class StoreAdminController {

    private final StoreRepository storeRepository;
    private final SessionService sessionService;

    public StoreAdminController(StoreRepository storeRepository, SessionService sessionService) {
        this.storeRepository = storeRepository;
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody JsonNode body) {

        if (!body.has("name") || body.get("name").asText().isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));

        String name = body.get("name").asText().trim();
        if (!name.matches("[a-zA-Z0-9_\\-]+"))
            return ResponseEntity.badRequest().body(new ErrorResponse("name may only contain letters, digits, hyphens, and underscores"));

        var session = sessionService.requireSession(auth);
        var rootId = storeRepository.findAdminRootStore(session.userId());
        if (rootId.isEmpty())
            return ResponseEntity.status(403).body(new ErrorResponse("No admin store found for this user"));

        long parentStoreId = body.has("parentStoreId")
            ? body.get("parentStoreId").asLong()
            : rootId.get();

        sessionService.requirePermission(auth, Permission.MANAGE_STORES, parentStoreId);

        String displayName = body.has("displayName") ? body.get("displayName").toString() : null;
        String currency = body.has("currency") && !body.get("currency").asText().isBlank()
            ? body.get("currency").asText().trim().toUpperCase()
            : null;

        try {
            long id = storeRepository.createStore(name, displayName, currency, parentStoreId);
            return ResponseEntity.ok(Map.of("id", id));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate") || msg.contains("duplicate")))
                return ResponseEntity.status(409).body(new ErrorResponse("Store '" + name + "' already exists"));
            throw e;
        }
    }

    @GetMapping("/{id}/admin")
    public ResponseEntity<?> getAdminDetails(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, id);
        var store = storeRepository.findByIdAdmin(id);
        if (store.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Store not found: " + id));
        return ResponseEntity.ok(store.get());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {

        sessionService.requirePermission(auth, Permission.MANAGE_STORES, id);

        if (storeRepository.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Store not found: " + id));
        }

        storeRepository.patch(id, body);
        return ResponseEntity.ok().build();
    }
}
