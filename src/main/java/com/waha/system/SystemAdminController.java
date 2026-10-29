package com.waha.system;

import com.waha.auth.Permission;
import com.waha.auth.PinLockoutService;
import com.waha.auth.SessionService;
import com.waha.auth.User;
import com.waha.auth.UserRepository;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.config.ConfigService;
import com.waha.device.LastSeenTracker;
import com.waha.integration.odoo.OdooOrderSyncService;
import com.waha.offer.OfferRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

// System Configuration (super-admin only, MANAGE_SYSTEM): global settings, cache actions, login lockouts and the
// system audit. See docs/system-configuration.md.
@RestController
@RequestMapping("/api/admin/system")
public class SystemAdminController {

    private static final List<String> CACHES = List.of("public-base-url", "offers", "last-seen", "odoo-partner");

    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final ConfigService configService;
    private final OfferRepository offerRepository;
    private final LastSeenTracker lastSeen;
    private final OdooOrderSyncService odooSync;
    private final PinLockoutService lockoutService;
    private final SystemAuditService audit;

    public SystemAdminController(SessionService sessionService, UserRepository userRepository, ConfigService configService,
                                 OfferRepository offerRepository, LastSeenTracker lastSeen, OdooOrderSyncService odooSync,
                                 PinLockoutService lockoutService, SystemAuditService audit) {
        this.sessionService = sessionService;
        this.userRepository = userRepository;
        this.configService = configService;
        this.offerRepository = offerRepository;
        this.lastSeen = lastSeen;
        this.odooSync = odooSync;
        this.lockoutService = lockoutService;
        this.audit = audit;
    }

    private UserSession requireSuperAdmin(String auth) {
        UserSession session = sessionService.requireSession(auth);
        if (!session.isUserSession()
                || !sessionService.resolvePermissions(session.userId(), null).contains(Permission.MANAGE_SYSTEM.name()))
            throw new ForbiddenException("Permission denied: MANAGE_SYSTEM");
        return session;
    }

    private String actorName(UserSession s) {
        return userRepository.findById(s.userId()).map(User::username).orElse("user " + s.userId());
    }

    private static String ip(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }

    private void record(UserSession s, HttpServletRequest req, String action, String target, String oldV, String newV, String details) {
        audit.record(s.userId(), actorName(s), action, target, oldV, newV, details, ip(req));
    }

    // ── settings ──────────────────────────────────────────────────────────────

    @GetMapping("/properties")
    public ResponseEntity<?> properties(@RequestHeader(value = "Authorization", required = false) String auth) {
        requireSuperAdmin(auth);
        return ResponseEntity.ok(propertiesView());
    }

    // Body: { "key": "value", ... } - known keys only, each validated; takes effect at once.
    @PutMapping("/properties")
    public ResponseEntity<?> updateProperties(@RequestHeader(value = "Authorization", required = false) String auth,
                                              @RequestBody Map<String, String> body, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        if (body == null || body.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("Request body must contain at least one setting"));
        Map<String, String> cleaned = new LinkedHashMap<>();
        try {
            for (var e : body.entrySet()) cleaned.put(e.getKey(), SystemPropertySpecs.clean(e.getKey(), e.getValue()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        for (var e : cleaned.entrySet()) {
            String key = e.getKey();
            String oldValue = configService.getGlobalProperty(key);
            if (key.equals("publicBaseUrl")) configService.setPublicBaseUrl(e.getValue());
            else configService.upsertOrgProperty(0L, key, e.getValue(), SystemPropertySpecs.KNOWN.get(key).description());
            if (!Objects.equals(oldValue == null ? "" : oldValue, e.getValue()))
                record(session, req, "SETTING_CHANGED", key, oldValue, e.getValue(), null);
        }
        return ResponseEntity.ok(propertiesView());
    }

    private Map<String, Object> propertiesView() {
        Map<String, String> all = configService.findAllProperties(0L);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var e : SystemPropertySpecs.KNOWN.entrySet()) {
            var spec = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("value", all.get(e.getKey()));
            m.put("default", spec.defaultValue());
            m.put("type", spec.type());
            m.put("description", spec.description());
            m.put("constraints", spec.constraints());
            items.add(m);
        }
        // Other global rows (defaults for organization settings): shown read-only.
        List<Map<String, Object>> others = new ArrayList<>();
        for (var e : all.entrySet()) {
            if (SystemPropertySpecs.KNOWN.containsKey(e.getKey()) || e.getKey().equals("api_level")) continue;
            others.add(Map.of("key", e.getKey(), "value", e.getValue() == null ? "" : e.getValue()));
        }
        return Map.of("items", items, "others", others);
    }

    // ── caches ────────────────────────────────────────────────────────────────

    @GetMapping("/caches")
    public ResponseEntity<?> caches(@RequestHeader(value = "Authorization", required = false) String auth) {
        requireSuperAdmin(auth);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(cacheRow("public-base-url", "Public base URL read from settings (used in invoice links)",
            configService.isPublicBaseUrlCached() ? 1 : 0, null, true, false));
        items.add(cacheRow("offers", "Active offers per store, kept for 60 seconds",
            offerRepository.cacheSize(), null, true, false));
        items.add(cacheRow("last-seen", "Last-seen times and app versions not yet written to the database",
            lastSeen.pendingCount(), lastSeen.lastFlushAt().toString(), false, true));
        items.add(cacheRow("odoo-partner", "Odoo customer lookup for orders", 0, null, true, false));
        return ResponseEntity.ok(Map.of("items", items));
    }

    private static Map<String, Object> cacheRow(String name, String description, int entries, String loadedAt,
                                                boolean canClear, boolean canSave) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("entries", entries);
        m.put("loadedAt", loadedAt);
        m.put("canClear", canClear);
        m.put("canSave", canSave);
        return m;
    }

    private void clearCache(String name) {
        switch (name) {
            case "public-base-url" -> configService.clearPublicBaseUrlCache();
            case "offers" -> offerRepository.clearCache();
            case "odoo-partner" -> odooSync.resetPartnerCache();
            default -> throw new IllegalArgumentException("Cache cannot be cleared: " + name);
        }
    }

    private int saveLastSeen() {
        int n = lastSeen.pendingCount();
        lastSeen.flush();
        return n;
    }

    @PostMapping("/caches/{name}/clear")
    public ResponseEntity<?> clear(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable String name, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        if (!CACHES.contains(name)) return ResponseEntity.status(404).body(new ErrorResponse("Unknown cache: " + name));
        try {
            clearCache(name);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        record(session, req, "CACHE_CLEARED", name, null, null, null);
        return ResponseEntity.ok(Map.of("cleared", true));
    }

    @PostMapping("/caches/{name}/save")
    public ResponseEntity<?> save(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @PathVariable String name, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        if (!CACHES.contains(name)) return ResponseEntity.status(404).body(new ErrorResponse("Unknown cache: " + name));
        if (!name.equals("last-seen")) return ResponseEntity.badRequest().body(new ErrorResponse("Cache cannot be saved: " + name));
        int n = saveLastSeen();
        record(session, req, "CACHE_SAVED", name, null, null, n + " session(s) written");
        return ResponseEntity.ok(Map.of("saved", n));
    }

    @PostMapping("/caches/clear-all")
    public ResponseEntity<?> clearAll(@RequestHeader(value = "Authorization", required = false) String auth, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        int n = 0;
        for (String name : CACHES) {
            if (name.equals("last-seen")) continue;
            clearCache(name);
            n++;
        }
        record(session, req, "CACHE_CLEARED", "all", null, null, n + " cache(s)");
        return ResponseEntity.ok(Map.of("cleared", n));
    }

    @PostMapping("/caches/save-all")
    public ResponseEntity<?> saveAll(@RequestHeader(value = "Authorization", required = false) String auth, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        int n = saveLastSeen();
        record(session, req, "CACHE_SAVED", "all", null, null, n + " session(s) written");
        return ResponseEntity.ok(Map.of("saved", n));
    }

    // ── login lockouts ────────────────────────────────────────────────────────

    @PostMapping("/lockouts/clear")
    public ResponseEntity<?> clearLockouts(@RequestHeader(value = "Authorization", required = false) String auth, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        int n = lockoutService.clearAll();
        record(session, req, "LOCKOUTS_CLEARED", null, null, null, n + " lockout(s) cleared");
        return ResponseEntity.ok(Map.of("cleared", n));
    }

    // ── audit ─────────────────────────────────────────────────────────────────

    @GetMapping("/audit")
    public ResponseEntity<?> auditList(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                                       @RequestParam(required = false) String action, @RequestParam(required = false) String actor,
                                       @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        requireSuperAdmin(auth);
        try {
            return ResponseEntity.ok(audit.list(page, size, action, actor, date(from), date(to)));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("from and to must be dates like 2026-10-30"));
        }
    }

    @DeleteMapping("/audit/{id}")
    public ResponseEntity<?> auditDelete(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable long id, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        int n = audit.delete(id);
        if (n == 0) return ResponseEntity.status(404).body(new ErrorResponse("Audit row not found"));
        record(session, req, "AUDIT_ROW_DELETED", String.valueOf(id), null, null, null);
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    // Body optional: { "before": "2026-01-01" }. Without it every row goes; one row is left saying who did it.
    @PostMapping("/audit/clear")
    public ResponseEntity<?> auditClear(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestBody(required = false) Map<String, String> body, HttpServletRequest req) {
        UserSession session = requireSuperAdmin(auth);
        LocalDate before;
        try {
            before = date(body == null ? null : body.get("before"));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("before must be a date like 2026-10-30"));
        }
        int n = audit.clear(before);
        record(session, req, "AUDIT_CLEARED", before == null ? "all" : "before " + before, null, null, n + " row(s) deleted");
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    private static LocalDate date(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
    }
}
