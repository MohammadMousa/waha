package com.waha.invoice;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

// Admin GET + PATCH for receipt_info. Requires MANAGE_STORES.
// GET returns the receipt info for the caller's session store.
// PATCH upserts — creates a new row if none exists for this store.
@RestController
@RequestMapping("/api/receipt-info")
public class ReceiptInfoAdminController {

    private final ReceiptInfoRepository receiptInfoRepository;
    private final SessionService sessionService;

    public ReceiptInfoAdminController(ReceiptInfoRepository receiptInfoRepository,
                                       SessionService sessionService) {
        this.receiptInfoRepository = receiptInfoRepository;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ResponseEntity<?> get(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "storeId", required = false) Long storeId) {

        Optional<UserSession> session = sessionService.tryResolveSession(auth);
        long resolvedStore = resolveStore(storeId, session);
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, resolvedStore);

        Optional<ReceiptInfo> info = receiptInfoRepository.findByStoreId(resolvedStore);
        if (info.isEmpty()) {
            return ResponseEntity.ok(Map.of("storeId", resolvedStore));
        }
        return ResponseEntity.ok(receiptInfoRepository.toResponse(info.get()));
    }

    @PatchMapping
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "storeId", required = false) Long storeId,
            @RequestBody Map<String, Object> body) {

        Optional<UserSession> session = sessionService.tryResolveSession(auth);
        long resolvedStore = resolveStore(storeId, session);
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, resolvedStore);

        receiptInfoRepository.upsert(resolvedStore, body);
        return ResponseEntity.ok().build();
    }

    private long resolveStore(Long explicit, Optional<UserSession> session) {
        if (explicit != null) return explicit;
        return session.map(UserSession::storeId).map(s -> s != null ? s : 1L).orElse(1L);
    }
}
