package com.waha.config;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.common.UnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin/organization/properties")
public class OrgPropertiesController {

    private static final int INTERVAL_MAX = 1440;

    // Known keys: key → PropertySpec(type, description, constraints)
    private static final Map<String, PropertySpec> KNOWN_KEYS = new LinkedHashMap<>();
    static {
        KNOWN_KEYS.put("default_language",
            new PropertySpec("string", "Default display language for the kiosk", "ar or en"));
        KNOWN_KEYS.put("check_landing_page_interval_minutes",
            new PropertySpec("integer", "How often the kiosk re-checks the landing page for changes", "1–" + INTERVAL_MAX + " minutes"));
        KNOWN_KEYS.put("auto_log_upload_enabled",
            new PropertySpec("boolean", "Kiosks upload their diagnostic logs automatically", "true or false"));
        KNOWN_KEYS.put("max_log_uploads_per_hour",
            new PropertySpec("integer", "Most automatic log uploads one kiosk may send per hour (default 3)", "1–60"));
        KNOWN_KEYS.put("log_harvest_seconds",
            new PropertySpec("integer", "Seconds of log the kiosk collects for each automatic upload (default 30)", "10–240 seconds"));
        KNOWN_KEYS.put("enable_logging",
            new PropertySpec("boolean", "Kiosks of this org turn on trace logging and uploads (default off)", "true or false"));
        KNOWN_KEYS.put("device_auto_restart_enabled",
            new PropertySpec("boolean", "Kiosks restart the app on a schedule (default off)", "true or false"));
        KNOWN_KEYS.put("device_auto_restart_hours",
            new PropertySpec("decimal", "Hours between scheduled app restarts (default 2)", "0.25–168 hours"));
        KNOWN_KEYS.put("device_auto_restart_wait_minutes",
            new PropertySpec("integer", "Minutes to wait before re-checking when the kiosk is busy or the user taps Not now (default 3)", "1–60 minutes"));
        KNOWN_KEYS.put("heartbeat_minutes",
            new PropertySpec("integer", "Minutes between kiosk heartbeat calls; 0 turns device monitoring off (default 0)", "0–1440 minutes"));
        KNOWN_KEYS.put("late_approval_expiration_minutes",
            new PropertySpec("integer", "A card approval arriving after the kiosk gave up still pays an unpaid order if the order is younger than this (default 3)", "1–60 minutes"));
        KNOWN_KEYS.put("prices_exclude_vat",
            new PropertySpec("boolean", "Show prices without VAT in the apps; by default prices are shown with VAT (default false)", "true or false"));
        KNOWN_KEYS.put("terminal_timeout_seconds",
            new PropertySpec("integer", "Seconds the kiosk waits for the card terminal; overrides the device setting (default 60)", "10–80 seconds"));
        // Offline mode (see docs/offline.md). Defaults live in OfflineDefaults and are always sent by GET /api/config.
        KNOWN_KEYS.put("offline_mode",
            new PropertySpec("boolean", "Kiosks keep selling from a local copy of the catalog and send orders later (default false)", "true or false"));
        KNOWN_KEYS.put("sync_pull_start_delay_seconds",
            new PropertySpec("integer", "Seconds the kiosk waits after starting before its first catalog pull (default 60)", "0–3600 seconds"));
        KNOWN_KEYS.put("sync_stagger_seconds",
            new PropertySpec("integer", "Kiosks spread their first sync after a start over this many seconds so they do not all hit the server together (default 180)", "30–3600 seconds"));
        KNOWN_KEYS.put("sync_pull_interval_minutes",
            new PropertySpec("integer", "Minutes between catalog pulls (default 60)", "1–1440 minutes"));
        KNOWN_KEYS.put("sync_pull_patch_size",
            new PropertySpec("integer", "Rows per catalog pull page (default 200)", "10–500 rows"));
        KNOWN_KEYS.put("sync_pull_timeout_seconds",
            new PropertySpec("integer", "Seconds the kiosk waits for one pull page (default 30)", "5–120 seconds"));
        KNOWN_KEYS.put("sync_idle_seconds",
            new PropertySpec("integer", "Seconds without scans or touches before the kiosk starts a pull (default 10)", "1–300 seconds"));
        KNOWN_KEYS.put("sync_push_interval_minutes",
            new PropertySpec("integer", "Minutes between attempts to send waiting orders; a paid order is also sent at once (default 3)", "1–60 minutes"));
        KNOWN_KEYS.put("sync_push_timeout_seconds",
            new PropertySpec("integer", "Seconds the kiosk waits for the server to accept an order (default 60)", "10–300 seconds"));
        KNOWN_KEYS.put("sync_push_retry_max_delay_minutes",
            new PropertySpec("integer", "Longest wait between retries of a failed order send (default 15)", "1–240 minutes"));
        KNOWN_KEYS.put("scan_disabled_products",
            new PropertySpec("boolean", "A disabled product can still be scanned and sold, online and offline (default false)", "true or false"));
        KNOWN_KEYS.put("offline_unpaid_cleanup_enabled",
            new PropertySpec("boolean", "Kiosks delete unpaid local orders older than the days offset (default false)", "true or false"));
        KNOWN_KEYS.put("offline_unpaid_cleanup_days_offset",
            new PropertySpec("integer", "Age in days; local unpaid orders (and audit info, when its cleanup is on) older than this are deleted (default 15)", "1–365 days"));
        KNOWN_KEYS.put("offline_audit_cleanup",
            new PropertySpec("boolean", "Kiosks delete local audit info of already-sent orders older than the days offset (default false)", "true or false"));
        KNOWN_KEYS.put("push_paid_orders_only",
            new PropertySpec("boolean", "Kiosks send only paid orders; unpaid ones stay on the device (default true)", "true or false"));
    }

    record PropertySpec(String type, String description, String constraints) {}

    private final ConfigService configService;
    private final SessionService sessionService;

    public OrgPropertiesController(ConfigService configService, SessionService sessionService) {
        this.configService  = configService;
        this.sessionService = sessionService;
    }

    // ── GET /api/admin/organization/properties ────────────────────────────────
    // Returns this org's own property rows (no global merge).
    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        try {
            UserSession session = require(auth);
            List<Map<String, Object>> rows = configService.listOrgProperties(session.organizationId());
            return ResponseEntity.ok(Map.of("items", rows));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    // ── GET /api/admin/organization/properties/keys ───────────────────────────
    // Lists the known property keys with metadata. Admin may also create free-form ones.
    @GetMapping("/keys")
    public ResponseEntity<?> keys(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        try {
            require(auth);
            List<Map<String, Object>> result = new ArrayList<>();
            for (var entry : KNOWN_KEYS.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key",         entry.getKey());
                m.put("type",        entry.getValue().type());
                m.put("description", entry.getValue().description());
                m.put("constraints", entry.getValue().constraints());
                m.put("default",     com.waha.sync.OfflineDefaults.VALUES.get(entry.getKey()));
                result.add(m);
            }
            return ResponseEntity.ok(Map.of("knownKeys", result));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    // ── PUT /api/admin/organization/properties ────────────────────────────────
    // Body: { "key": "value", ... } — only keys being changed.
    // Known keys are validated; free-form keys pass through (admin's responsibility).
    @PutMapping
    public ResponseEntity<?> upsert(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, String> body) {
        try {
            UserSession session = require(auth);
            if (body == null || body.isEmpty()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Request body must contain at least one property"));
            }
            for (var entry : body.entrySet()) {
                String error = validate(entry.getKey(), entry.getValue());
                if (error != null) {
                    return ResponseEntity.badRequest().body(new ErrorResponse(error));
                }
            }
            long orgId = session.organizationId();
            for (var entry : body.entrySet()) {
                String desc = KNOWN_KEYS.containsKey(entry.getKey())
                    ? KNOWN_KEYS.get(entry.getKey()).description() : "";
                configService.upsertOrgProperty(orgId, entry.getKey(), entry.getValue(), desc);
            }
            List<Map<String, Object>> updated = configService.listOrgProperties(orgId);
            return ResponseEntity.ok(Map.of("items", updated));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    // ── DELETE /api/admin/organization/properties/{key} ───────────────────────
    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String key) {
        try {
            UserSession session = require(auth);
            configService.deleteOrgProperty(session.organizationId(), key);
            return ResponseEntity.noContent().build();
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private String validate(String key, String value) {
        if (key == null || key.isBlank()) return "Property key must not be blank";
        if (value == null)               return "Value for '" + key + "' must not be null";
        return switch (key) {
            case "default_language" -> {
                String v = value.toLowerCase();
                yield (v.equals("ar") || v.equals("en")) ? null
                    : "default_language must be 'ar' or 'en'";
            }
            case "check_landing_page_interval_minutes" -> {
                try {
                    int v = Integer.parseInt(value.trim());
                    if (v < 1)           yield "check_landing_page_interval_minutes must be at least 1";
                    if (v > INTERVAL_MAX) yield "check_landing_page_interval_minutes must be at most " + INTERVAL_MAX;
                    yield null;
                } catch (NumberFormatException e) {
                    yield "check_landing_page_interval_minutes must be an integer";
                }
            }
            case "auto_log_upload_enabled" -> {
                String v = value.trim().toLowerCase();
                yield (v.equals("true") || v.equals("false")) ? null
                    : "auto_log_upload_enabled must be 'true' or 'false'";
            }
            case "sync_pull_start_delay_seconds"     -> intInRange(key, value, 0, 3600);
            case "sync_stagger_seconds"              -> intInRange(key, value, 30, 3600);
            case "sync_pull_interval_minutes"        -> intInRange(key, value, 1, 1440);
            case "sync_pull_patch_size"              -> intInRange(key, value, 10, 500);
            case "sync_pull_timeout_seconds"         -> intInRange(key, value, 5, 120);
            case "sync_idle_seconds"                 -> intInRange(key, value, 1, 300);
            case "sync_push_interval_minutes"        -> intInRange(key, value, 1, 60);
            case "sync_push_timeout_seconds"         -> intInRange(key, value, 10, 300);
            case "sync_push_retry_max_delay_minutes" -> intInRange(key, value, 1, 240);
            case "offline_unpaid_cleanup_days_offset" -> intInRange(key, value, 1, 365);
            case "enable_logging", "device_auto_restart_enabled", "prices_exclude_vat",
                 "offline_mode", "push_paid_orders_only", "scan_disabled_products",
                 "offline_unpaid_cleanup_enabled", "offline_audit_cleanup" -> {
                String v = value.trim().toLowerCase();
                yield (v.equals("true") || v.equals("false")) ? null
                    : key + " must be 'true' or 'false'";
            }
            case "device_auto_restart_hours" -> {
                try {
                    double v = Double.parseDouble(value.trim());
                    if (v < 0.25 || v > 168) yield key + " must be between 0.25 and 168";
                    yield null;
                } catch (NumberFormatException e) {
                    yield key + " must be a number";
                }
            }
            case "device_auto_restart_wait_minutes" -> intInRange(key, value, 1, 60);
            case "heartbeat_minutes"                -> intInRange(key, value, 0, 1440);
            case "late_approval_expiration_minutes" -> intInRange(key, value, 1, 60);
            case "terminal_timeout_seconds"         -> intInRange(key, value, 10, 80);
            case "max_log_uploads_per_hour" -> intInRange(key, value, 1, 60);
            case "log_harvest_seconds"      -> intInRange(key, value, 10, 240);
            default -> null; // free-form key — pass through
        };
    }

    private static String intInRange(String key, String value, int min, int max) {
        try {
            int v = Integer.parseInt(value.trim());
            if (v < min) return key + " must be at least " + min;
            if (v > max) return key + " must be at most " + max;
            return null;
        } catch (NumberFormatException e) {
            return key + " must be an integer";
        }
    }

    private UserSession require(String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, session.storeId());
        return session;
    }
}
