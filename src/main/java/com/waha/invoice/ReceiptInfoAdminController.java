package com.waha.invoice;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

// Admin GET + PATCH for receipt_info. Now organization-level (not store-level).
// GET/PATCH use ?organizationId — defaults to 1 when omitted.
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
            @RequestParam(value = "organizationId", required = false) Long organizationId) {

        long orgId = resolveOrg(organizationId);
        sessionService.requirePermissionForOrg(auth, Permission.MANAGE_STORES, orgId);

        Optional<ReceiptInfo> info = receiptInfoRepository.findByOrganizationId(orgId);
        if (info.isEmpty()) {
            return ResponseEntity.ok(Map.of("organizationId", orgId));
        }
        return ResponseEntity.ok(receiptInfoRepository.toResponse(info.get()));
    }

    @PatchMapping
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "organizationId", required = false) Long organizationId,
            @RequestBody Map<String, Object> body) {

        long orgId = resolveOrg(organizationId);
        sessionService.requirePermissionForOrg(auth, Permission.MANAGE_STORES, orgId);

        receiptInfoRepository.upsert(orgId, body);
        return ResponseEntity.ok().build();
    }

    private long resolveOrg(Long explicit) {
        return explicit != null ? explicit : 1L;
    }
}
