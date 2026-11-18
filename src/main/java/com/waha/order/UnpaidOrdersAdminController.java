package com.waha.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.audit.AuditService;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Deletes unpaid orders by hand. Paid orders, and orders with a payment in progress, are never deleted.
// Needs DELETE_UNPAID_ORDERS in the organization. The daily cleanup of old unpaid orders is a separate system setting.
@RestController
@RequestMapping("/api/admin/orders/unpaid")
public class UnpaidOrdersAdminController {

    private static final int MAX_IDS = 1000;
    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 200;

    private final SessionService sessionService;
    private final UnpaidOrderRepository unpaid;
    private final AuditService audit;

    public UnpaidOrdersAdminController(SessionService sessionService, UnpaidOrderRepository unpaid, AuditService audit) {
        this.sessionService = sessionService;
        this.unpaid = unpaid;
        this.audit = audit;
    }

    private UserSession authorize(String auth) {
        UserSession s = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(s, Permission.DELETE_UNPAID_ORDERS, s.organizationId());
        return s;
    }

    private static ResponseEntity<?> orgRequired() {
        return ResponseEntity.status(409).body(Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));
    }

    // How many unpaid orders a delete would remove (older than N days, optionally one branch).
    @GetMapping("/count")
    public ResponseEntity<?> count(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestParam(defaultValue = "1") int olderThanDays,
                                   @RequestParam(required = false) Long branchId) {
        UserSession s = authorize(auth);
        if (s.organizationId() <= 0) return orgRequired();
        if (olderThanDays < 1 || olderThanDays > 3650) return ResponseEntity.badRequest().body(new ErrorResponse("olderThanDays must be 1 to 3650"));
        return ResponseEntity.ok(Map.of("count", unpaid.countOlderThan(olderThanDays, s.organizationId(), branchId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOne(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable String id) {
        UserSession s = authorize(auth);
        if (s.organizationId() <= 0) return orgRequired();
        int n = unpaid.delete(List.of(id), s.organizationId());
        if (n == 0) {
            return ResponseEntity.status(409).body(new ErrorResponse(
                "Order not deleted: it does not exist, is paid, or has a payment in progress"));
        }
        audit.record(AuditService.ORGANIZATION, s.organizationId(), "ORDER", id, s.userId(), null, "UNPAID_ORDER_DELETED",
            id, null, null, null);
        return ResponseEntity.ok(Map.of("deleted", 1));
    }

    // Body: { "ids": ["...", ...] } (up to 1000), or { "olderThanDays": 30, "branchId": 5 } (branchId optional).
    @PostMapping("/delete")
    public ResponseEntity<?> deleteMany(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody JsonNode body) {
        UserSession s = authorize(auth);
        long org = s.organizationId();
        if (org <= 0) return orgRequired();
        boolean byIds = body.has("ids") && body.get("ids").isArray();
        boolean byAge = body.hasNonNull("olderThanDays");
        if (byIds == byAge) return ResponseEntity.badRequest().body(new ErrorResponse("Send either ids or olderThanDays"));

        long deleted = 0;
        String details;
        if (byIds) {
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode n : body.get("ids")) if (n.isTextual() && !n.asText().isBlank()) ids.add(n.asText().trim());
            if (ids.isEmpty()) return ResponseEntity.badRequest().body(new ErrorResponse("ids is empty"));
            if (ids.size() > MAX_IDS) return ResponseEntity.badRequest().body(new ErrorResponse("At most " + MAX_IDS + " ids at a time"));
            List<String> list = new ArrayList<>(ids);
            for (int i = 0; i < list.size(); i += BATCH) deleted += unpaid.delete(list.subList(i, Math.min(i + BATCH, list.size())), org);
            details = deleted + " of " + ids.size() + " selected orders";
            return finish(s, org, deleted, ids.size() - deleted, details);
        }
        int days = body.get("olderThanDays").asInt(0);
        if (days < 1 || days > 3650) return ResponseEntity.badRequest().body(new ErrorResponse("olderThanDays must be 1 to 3650"));
        Long branch = body.hasNonNull("branchId") ? body.get("branchId").asLong() : null;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<String> ids = unpaid.findOlderThan(days, org, branch, BATCH);
            if (ids.isEmpty()) break;
            int n = unpaid.delete(ids, org);
            deleted += n;
            if (n == 0 || ids.size() < BATCH) break;
        }
        details = "older than " + days + " days" + (branch != null ? ", branch " + branch : "");
        return finish(s, org, deleted, 0, details);
    }

    private ResponseEntity<?> finish(UserSession s, long org, long deleted, long skipped, String details) {
        if (deleted > 0) {
            audit.record(AuditService.ORGANIZATION, org, "ORDER", null, s.userId(), null, "UNPAID_ORDERS_DELETED",
                deleted + " order(s)", null, null, details);
        }
        return ResponseEntity.ok(Map.of("deleted", deleted, "skipped", skipped));
    }
}
