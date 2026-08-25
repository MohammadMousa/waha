package com.waha.store;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.UnauthorizedException;
import com.waha.store.dto.StoreSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreRepository storeRepository;
    private final SessionService sessionService;

    public StoreController(StoreRepository storeRepository, SessionService sessionService) {
        this.storeRepository = storeRepository;
        this.sessionService = sessionService;
    }

    // The store picker after login. Only ever returns public, active,
    // operational (CHILD) locations - never a PARENT grouping node or a
    // WAREHOUSE, since neither is somewhere a customer would ever check
    // out from.
    @GetMapping
    public List<StoreSummary> listPublicStores() {
        return storeRepository.findPublicStores();
    }

    // Admin store picker: returns all active stores in the caller's realm
    // (the subtree rooted at the store where their admin role is assigned).
    // Requires MANAGE_STORES permission.
    @GetMapping("/admin")
    public ResponseEntity<?> listAdminStores(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            UserSession session = sessionService.requireSession(authHeader);
            Set<String> perms = sessionService.resolvePermissions(session.userId(), session.storeId());
            if (!perms.contains(Permission.MANAGE_STORES.name())) {
                return ResponseEntity.status(403).body(new ErrorResponse("Forbidden"));
            }
            Long adminRoot = storeRepository.findAdminRootStore(session.userId()).orElse(null);
            if (adminRoot == null) {
                return ResponseEntity.status(403).body(new ErrorResponse("No admin store assignment found"));
            }
            return ResponseEntity.ok(storeRepository.findAdminStores(adminRoot));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }
}
