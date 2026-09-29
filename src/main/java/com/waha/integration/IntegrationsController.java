package com.waha.integration;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/integrations")
public class IntegrationsController {

    private final SessionService sessionService;
    private final IntegrationsAdminRepository integrationsAdminRepository;

    public IntegrationsController(SessionService sessionService,
                                  IntegrationsAdminRepository integrationsAdminRepository) {
        this.sessionService = sessionService;
        this.integrationsAdminRepository = integrationsAdminRepository;
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String  entityType,
            @RequestParam(required = false) String  status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, session.storeId());
        if (size < 1 || size > 200) size = 20;

        long orgId = session.organizationId();
        long total = integrationsAdminRepository.countLogs(orgId, entityType, status);
        List<Map<String, Object>> items = integrationsAdminRepository.getLogs(orgId, entityType, status, page, size);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items",      items);
        out.put("totalCount", total);
        out.put("page",       page);
        out.put("size",       size);
        return ResponseEntity.ok(out);
    }

    public record DeleteLogsRequest(List<Long> ids) {}

    // Deletes finished history rows. Refused, with a reason per id:
    // rows still waiting (PENDING), and orders already sent to Odoo, which the
    // sales reports use to show an order as "sent to Odoo".
    // A FAILED order row can be deleted, but then it can never be retried.
    @RequestMapping(value = {"/logs", "/logs/delete"}, method = {RequestMethod.DELETE, RequestMethod.POST})
    public ResponseEntity<?> deleteLogs(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody DeleteLogsRequest request) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, session.storeId());
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            return ResponseEntity.badRequest().body(new com.waha.common.ErrorResponse("ids is required"));
        }
        if (request.ids().size() > 500) {
            return ResponseEntity.badRequest().body(new com.waha.common.ErrorResponse("At most 500 ids per request"));
        }

        List<Long> deletable = new ArrayList<>();
        List<Map<String, Object>> refused = new ArrayList<>();
        java.util.Set<Long> found = new java.util.HashSet<>();
        for (IntegrationsAdminRepository.LogRow row : integrationsAdminRepository.findLogs(session.organizationId(), request.ids())) {
            found.add(row.id());
            String reason = null;
            if ("PENDING".equals(row.status())) {
                reason = "STILL_PENDING";
            } else if ("ORDER".equals(row.entityType()) && "DONE".equals(row.status())) {
                reason = "ORDER_SENT_TO_ODOO";
            }
            if (reason == null) deletable.add(row.id());
            else refused.add(Map.of("id", row.id(), "reason", reason));
        }
        for (Long id : request.ids()) {
            if (!found.contains(id)) refused.add(Map.of("id", id, "reason", "NOT_FOUND"));
        }

        int deleted = integrationsAdminRepository.deleteLogs(deletable);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("refused", refused);
        return ResponseEntity.ok(out);
    }
}
