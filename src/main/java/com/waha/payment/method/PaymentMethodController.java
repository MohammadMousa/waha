package com.waha.payment.method;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payment-methods")
public class PaymentMethodController {

    private static final List<String> VALID_MODES = List.of("NORMAL", "KIOSK", "SHOPPING");

    private final PaymentMethodRepository paymentMethodRepository;
    private final SessionService sessionService;

    public PaymentMethodController(PaymentMethodRepository paymentMethodRepository, SessionService sessionService) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.sessionService = sessionService;
    }

    // Returns the payment methods available at a store for a given mode.
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Long storeId,
                                   @RequestHeader(value = "Authorization", required = false) String authHeader,
                                   @RequestParam(required = false) String mode) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }
        if (mode == null || mode.isBlank()) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode is required (NORMAL, KIOSK, or SHOPPING)"));
        }
        String upperMode = mode.toUpperCase();
        if (!VALID_MODES.contains(upperMode)) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode must be one of: NORMAL, KIOSK, SHOPPING"));
        }
        List<PaymentMethodSummary> methods = paymentMethodRepository.findForStore(resolvedStoreId, upperMode);
        return ResponseEntity.ok(methods);
    }

    // Admin: all globally-active methods for a store with their per-store
    // enabled state. Requires MANAGE_STORES permission at the target store.
    @GetMapping("/admin")
    public ResponseEntity<?> adminList(@RequestParam(required = false) Long storeId,
                                        @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse("storeId is required"));
        }
        try {
            sessionService.requirePermission(authHeader, Permission.MANAGE_STORES, resolvedStoreId);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.ok(paymentMethodRepository.findAllForAdmin(resolvedStoreId));
    }

    // Admin: enable or disable a payment method for the current store.
    // Upserts a row in payment_methods_store. Requires MANAGE_STORES.
    @PutMapping("/{id}/store-active")
    public ResponseEntity<?> setStoreActive(@PathVariable long id,
                                             @RequestParam(required = false) Long storeId,
                                             @RequestParam boolean active,
                                             @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse("storeId is required"));
        }
        try {
            sessionService.requirePermission(authHeader, Permission.MANAGE_STORES, resolvedStoreId);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        paymentMethodRepository.setStoreActive(id, resolvedStoreId, active);
        return ResponseEntity.ok().build();
    }
}
