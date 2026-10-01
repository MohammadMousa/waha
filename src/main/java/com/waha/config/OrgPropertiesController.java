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
