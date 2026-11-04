package com.waha.coupon;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.offer.OfferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Admin dashboard: coupons of the caller's organization (a super-admin picks one with X-Organization-Id).
@RestController
@RequestMapping("/api/admin/coupons")
public class CouponAdminController {

    private static final Logger log = LoggerFactory.getLogger(CouponAdminController.class);

    private final CouponAdminRepository repo;
    private final CouponAdminService service;
    private final SessionService sessionService;

    public CouponAdminController(CouponAdminRepository repo, CouponAdminService service, SessionService sessionService) {
        this.repo = repo;
        this.service = service;
        this.sessionService = sessionService;
    }

    private static final class Ctx {
        final UserSession session;
        final long org;
        Ctx(UserSession session) { this.session = session; this.org = session.organizationId(); }
        Long userId() { return session.userId() > 0 ? session.userId() : null; }
    }

    private Ctx ctx(String auth, Permission permission) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, permission, session.organizationId());
        return new Ctx(session);
    }

    private static ResponseEntity<?> orgRequired() {
        return ResponseEntity.status(409).body(Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) Long employeeId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size) {
        Ctx c = ctx(auth, Permission.VIEW_COUPONS);
        if (c.org <= 0) return orgRequired();
        size = Math.min(Math.max(size, 1), 200);
        page = Math.max(page, 0);
        var result = repo.list(c.org, status, q, employeeId, service.clock().now(), page, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result.items());
        out.put("total", result.total());
        out.put("page", page);
        out.put("size", size);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id) {
        Ctx c = ctx(auth, Permission.VIEW_COUPONS);
        if (c.org <= 0) return orgRequired();
        return repo.find(c.org, id, service.clock().now()).<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(CouponAdminController::notFound);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody CouponAdminService.Request body) {
        Ctx c = ctx(auth, Permission.MANAGE_COUPONS);
        if (c.org <= 0) return orgRequired();
        try {
            var fields = service.validate(body, c.org, null, null, null);
            long id = service.create(c.org, fields, c.userId());
            var created = repo.find(c.org, id, service.clock().now()).orElseThrow();
            repo.audit(id, c.userId(), "CREATE", null, created);
            log.info("Coupon {} created by user {}", id, c.userId());
            return ResponseEntity.status(201).body(created);
        } catch (OfferService.ValidationException e) {
            return invalid(e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id, @RequestBody CouponAdminService.Request body) {
        Ctx c = ctx(auth, Permission.MANAGE_COUPONS);
        if (c.org <= 0) return orgRequired();
        Optional<Map<String, Object>> before = repo.find(c.org, id, service.clock().now());
        if (before.isEmpty()) return notFound();
        try {
            var fields = service.validate(body, c.org, id, (String) before.get().get("type"), (Boolean) before.get().get("enabled"));
            service.update(c.org, id, fields, c.userId());
            var after = repo.find(c.org, id, service.clock().now()).orElseThrow();
            repo.audit(id, c.userId(), "UPDATE", before.get(), after);
            log.info("Coupon {} changed by user {}", id, c.userId());
            return ResponseEntity.ok(after);
        } catch (OfferService.ValidationException e) {
            return invalid(e);
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @PathVariable long id, @RequestBody Map<String, Object> body) {
        Ctx c = ctx(auth, Permission.MANAGE_COUPONS);
        if (c.org <= 0) return orgRequired();
        Optional<Map<String, Object>> before = repo.find(c.org, id, service.clock().now());
        if (before.isEmpty()) return notFound();
        if (!(body.get("enabled") instanceof Boolean enabled))
            return ResponseEntity.badRequest().body(new ErrorResponse("enabled must be true or false"));
        repo.setEnabled(id, enabled, c.userId());
        var after = repo.find(c.org, id, service.clock().now()).orElseThrow();
        repo.audit(id, c.userId(), enabled ? "ENABLE" : "DISABLE", before.get(), after);
        return ResponseEntity.ok(after);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id) {
        Ctx c = ctx(auth, Permission.MANAGE_COUPONS);
        if (c.org <= 0) return orgRequired();
        Optional<Map<String, Object>> before = repo.find(c.org, id, service.clock().now());
        if (before.isEmpty()) return notFound();
        if (repo.hasRedemptions(id))
            return ResponseEntity.status(409).body(Map.of("error", "COUPON_IN_USE",
                "message", "This coupon has been used by orders, so it cannot be deleted. Disable it instead"));
        repo.softDelete(id, c.userId());
        repo.audit(id, c.userId(), "DELETE", before.get(), null);
        log.info("Coupon {} deleted by user {}", id, c.userId());
        return ResponseEntity.noContent().build();
    }

    // Many codes with one configuration (listed or random), all or nothing.
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody CouponAdminService.Request body) {
        Ctx c = ctx(auth, Permission.MANAGE_COUPONS);
        if (c.org <= 0) return orgRequired();
        try {
            List<CouponAdminService.Created> created = service.createMany(c.org, body, c.userId());
            log.info("{} coupons created in one call by user {}", created.size(), c.userId());
            return ResponseEntity.status(201).body(Map.of("created", created));
        } catch (OfferService.ValidationException e) {
            return invalid(e);
        }
    }

    @GetMapping("/{id}/redemptions")
    public ResponseEntity<?> redemptions(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable long id,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Ctx c = ctx(auth, Permission.VIEW_COUPONS);
        if (c.org <= 0) return orgRequired();
        if (repo.find(c.org, id, service.clock().now()).isEmpty()) return notFound();
        size = Math.min(Math.max(size, 1), 200);
        page = Math.max(page, 0);
        var result = repo.redemptions(id, page, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result.items());
        out.put("total", result.total());
        out.put("page", page);
        out.put("size", size);
        return ResponseEntity.ok(out);
    }

    private static ResponseEntity<?> invalid(OfferService.ValidationException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Validation failed");
        out.put("errors", e.errors());
        return ResponseEntity.badRequest().body(out);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(new ErrorResponse("Coupon not found"));
    }
}
