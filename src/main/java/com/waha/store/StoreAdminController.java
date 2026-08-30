package com.waha.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
