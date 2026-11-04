package com.waha.offer;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Admin dashboard: offers of the caller's client (organization). See docs/offers.md.
@RestController
@RequestMapping("/api/admin/offers")
public class OfferAdminController {

    private static final Logger log = LoggerFactory.getLogger(OfferAdminController.class);

    private final OfferRepository repo;
    private final OfferService service;
    private final SessionService sessionService;

    public OfferAdminController(OfferRepository repo, OfferService service, SessionService sessionService) {
        this.repo = repo;
        this.service = service;
        this.sessionService = sessionService;
    }

    private UserSession session(String auth, Permission permission, Long clientId) {
        UserSession session = sessionService.requireSession(auth);
        long orgId = clientId != null ? clientId : session.organizationId();
        sessionService.requirePermissionForOrg(session, permission, orgId);
        return session;
    }

    private static Long userId(UserSession s) {
        return s.userId() > 0 ? s.userId() : null;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UserSession s = session(auth, Permission.VIEW_OFFERS, clientId);
        if (size < 1) size = 10;
        if (size > 500) size = 500;
        if (page < 0) page = 0;
        long org = clientId != null ? clientId : s.organizationId();
        var result = repo.list(org, status, q, service.clock().today(), page, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result.items());
        out.put("totalCount", result.totalCount());
        out.put("page", page);
        out.put("size", size);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @PathVariable long id) {
        UserSession s = sessionService.requireSession(auth);
        Optional<Long> org = repo.organizationOf(id);
        if (org.isEmpty()) return notFound();
        sessionService.requirePermissionForOrg(s, Permission.VIEW_OFFERS, org.get());
        return repo.find(id, service.clock().today()).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(OfferAdminController::notFound);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody OfferService.Request body) {
        UserSession s = session(auth, Permission.MANAGE_OFFERS, body.clientId());
        long org = body.clientId() != null ? body.clientId() : s.organizationId();
        try {
            var fields = service.validate(body, org, null);
            long id = repo.insert(fields, userId(s));
            var created = repo.find(id, service.clock().today()).orElseThrow();
            repo.audit(id, userId(s), "CREATE", null, created);
            log.info("Offer {} created by user {}", id, userId(s));
            return ResponseEntity.status(201).body(created);
        } catch (OfferService.ValidationException e) {
            return invalid(e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id, @RequestBody OfferService.Request body) {
        UserSession s = sessionService.requireSession(auth);
        Optional<Long> org = repo.organizationOf(id);
        if (org.isEmpty()) return notFound();
        sessionService.requirePermissionForOrg(s, Permission.MANAGE_OFFERS, org.get());
        var before = repo.find(id, service.clock().today()).orElseThrow();
        try {
            var fields = service.validate(body, org.get(), (String) before.get("type"));
            repo.update(id, fields, userId(s));
            var after = repo.find(id, service.clock().today()).orElseThrow();
            repo.audit(id, userId(s), "UPDATE", before, after);
            log.info("Offer {} changed by user {}", id, userId(s));
            return ResponseEntity.ok(after);
        } catch (OfferService.ValidationException e) {
            return invalid(e);
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable long id, @RequestBody Map<String, Object> body) {
        UserSession s = sessionService.requireSession(auth);
        Optional<Long> org = repo.organizationOf(id);
        if (org.isEmpty()) return notFound();
        sessionService.requirePermissionForOrg(s, Permission.MANAGE_OFFERS, org.get());
        if (!(body.get("enabled") instanceof Boolean enabled))
            return ResponseEntity.badRequest().body(new ErrorResponse("enabled must be true or false"));
        var before = repo.find(id, service.clock().today()).orElseThrow();
        repo.setEnabled(id, enabled, userId(s));
        var after = repo.find(id, service.clock().today()).orElseThrow();
        repo.audit(id, userId(s), enabled ? "ENABLE" : "DISABLE", before, after);
        log.info("Offer {} {} by user {}", id, enabled ? "enabled" : "disabled", userId(s));
        return ResponseEntity.ok(after);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id) {
        UserSession s = sessionService.requireSession(auth);
        Optional<Long> org = repo.organizationOf(id);
        if (org.isEmpty()) return notFound();
        sessionService.requirePermissionForOrg(s, Permission.MANAGE_OFFERS, org.get());
        var before = repo.find(id, service.clock().today()).orElseThrow();
        repo.softDelete(id, userId(s));
        repo.audit(id, userId(s), "DELETE", before, null);
        log.info("Offer {} deleted by user {}", id, userId(s));
        return ResponseEntity.noContent().build();
    }

    // Body: the draft offer fields (as for create) plus "sample": { "cartTotal": 100 } or
    // { "lines": [ { "productId": 1, "quantity": 2 } ] }
    public record PreviewRequest(OfferService.Request offer, BigDecimal cartTotal, List<OfferService.SampleLine> lines) {}

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody PreviewRequest body) {
        if (body.offer() == null) return ResponseEntity.badRequest().body(new ErrorResponse("offer is required"));
        UserSession s = session(auth, Permission.VIEW_OFFERS, body.offer().clientId());
        long org = body.offer().clientId() != null ? body.offer().clientId() : s.organizationId();
        try {
            var fields = service.validate(body.offer(), org, null);
            return ResponseEntity.ok(service.preview(fields, body.lines(), body.cartTotal()));
        } catch (OfferService.ValidationException e) {
            return invalid(e);
        }
    }

    private static ResponseEntity<?> invalid(OfferService.ValidationException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Validation failed");
        out.put("errors", e.errors());
        return ResponseEntity.badRequest().body(out);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(new ErrorResponse("Offer not found"));
    }
}
