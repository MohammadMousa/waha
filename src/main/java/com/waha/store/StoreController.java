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
import java.util.Collections;

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

    // Admin store picker: returns all active stores within the caller's scope.
    // Checks store-level permissions first (session store), then falls back to org-level
    // for ORGANIZATION_OWNER (COMPANY scope, no specific store in session yet).
    @GetMapping("/admin")
    public ResponseEntity<?> listAdminStores(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            UserSession session = sessionService.requireSession(authHeader);
            Set<String> perms = sessionService.resolvePermissions(session.userId(), session.storeId());
            if (!perms.contains(Permission.MANAGE_STORES.name())) {
                perms = sessionService.resolvePermissionsForOrg(session.userId(), session.organizationId());
                if (!perms.contains(Permission.MANAGE_STORES.name())) {
                    return ResponseEntity.status(403).body(new ErrorResponse("Forbidden"));
                }
            }
            List<StoreSummary> stores = storeRepository.findManageableStores(session.userId());
            return ResponseEntity.ok(stores.isEmpty() ? Collections.emptyList() : stores);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }
}
