package com.waha.audit;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Audit history. The super admin reads everything (All / System / Organization) and is the only one who can delete.
// An organization owner, or anyone holding VIEW_ORGANIZATION_AUDIT, reads only their own organization's history.
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private static final int MAX_DELETE = 500;

    private final SessionService sessionService;
    private final AuditService audit;

    public AuditController(SessionService sessionService, AuditService audit) {
        this.sessionService = sessionService;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                                  @RequestParam(required = false) String scope, @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String entityId, @RequestParam(required = false) String action,
                                  @RequestParam(required = false) String actor, @RequestParam(required = false) Long organizationId,
                                  @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        UserSession session = userSession(auth);
        String wanted = scope == null || scope.isBlank() ? "ALL" : scope.trim().toUpperCase();
        if (!Set.of("ALL", AuditService.SYSTEM, AuditService.ORGANIZATION).contains(wanted))
            return ResponseEntity.badRequest().body(new ErrorResponse("scope must be ALL, SYSTEM or ORGANIZATION"));

        String useScope;
        Long useOrg;
        if (isSuperAdmin(session)) {
            useScope = wanted;
            useOrg = organizationId;
            // Working inside one organization (X-Organization-Id): the organization view follows it.
            if (useOrg == null && AuditService.ORGANIZATION.equals(wanted) && session.organizationId() > 0)
                useOrg = session.organizationId();
        } else {
            long org = session.organizationId();
            if (org <= 0) throw new ForbiddenException("Permission denied: VIEW_ORGANIZATION_AUDIT");
            sessionService.requirePermissionForOrg(session, Permission.VIEW_ORGANIZATION_AUDIT, org);
            if (AuditService.SYSTEM.equals(wanted)) throw new ForbiddenException("Permission denied: system audit is for the super admin");
            useScope = AuditService.ORGANIZATION;
            useOrg = org;
        }
        if (useOrg != null && "ALL".equals(useScope)) useScope = AuditService.ORGANIZATION;
        try {
            return ResponseEntity.ok(audit.list(new AuditService.Query(useScope, useOrg, type, entityId, action, actor,
                date(from), date(to), page, size)));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("from and to must be dates like 2026-10-30"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOne(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable long id) {
        UserSession session = superAdmin(auth);
        int n = audit.delete(List.of(id), null);
        if (n == 0) return ResponseEntity.status(404).body(new ErrorResponse("Audit row not found"));
        recordDelete(session, "id " + id, n);
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    // Body: { "ids": [1, 2, 3] }, at most 500.
    @PostMapping("/delete")
    public ResponseEntity<?> deleteMany(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody Map<String, List<Long>> body) {
        UserSession session = superAdmin(auth);
        List<Long> ids = body == null ? null : body.get("ids");
        if (ids == null || ids.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("ids is required"));
        if (ids.size() > MAX_DELETE)
            return ResponseEntity.badRequest().body(new ErrorResponse("At most " + MAX_DELETE + " ids per request"));
        int n = audit.delete(new ArrayList<>(new LinkedHashSet<>(ids)), null);
        recordDelete(session, ids.size() + " id(s)", n);
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    // Body optional: { "before": "2026-01-01", "scope": "ALL|SYSTEM|ORGANIZATION" }. Without a date every row in the scope goes.
    @PostMapping("/clear")
    public ResponseEntity<?> clear(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody(required = false) Map<String, String> body) {
        UserSession session = superAdmin(auth);
        LocalDate before;
        try {
            String b = body == null ? null : body.get("before");
            before = b == null || b.isBlank() ? null : LocalDate.parse(b.trim());
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("before must be a date like 2026-10-30"));
        }
        String scope = body == null || body.get("scope") == null || body.get("scope").isBlank()
            ? "ALL" : body.get("scope").trim().toUpperCase();
        if (!Set.of("ALL", AuditService.SYSTEM, AuditService.ORGANIZATION).contains(scope))
            return ResponseEntity.badRequest().body(new ErrorResponse("scope must be ALL, SYSTEM or ORGANIZATION"));
        int n = audit.clear(before, scope);
        recordDelete(session, (before == null ? "all " : "before " + before + ", ") + scope, n);
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    private void recordDelete(UserSession session, String what, int n) {
        audit.record(AuditService.SYSTEM, 0, "SYSTEM", null, session.userId(), null, "AUDIT_ROWS_DELETED", what,
            null, null, n + " row(s) deleted");
    }

    private UserSession userSession(String auth) {
        UserSession session = sessionService.requireSession(auth);
        if (!session.isUserSession()) throw new ForbiddenException("Permission denied");
        return session;
    }

    private UserSession superAdmin(String auth) {
        UserSession session = userSession(auth);
        if (!isSuperAdmin(session)) throw new ForbiddenException("Permission denied: MANAGE_SYSTEM");
        return session;
    }

    private boolean isSuperAdmin(UserSession session) {
        return sessionService.resolvePermissions(session.userId(), null).contains(Permission.MANAGE_SYSTEM.name());
    }

    private static LocalDate date(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
    }
}
