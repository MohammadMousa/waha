package com.waha.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/devices")
public class DeviceAdminController {

    private final DeviceRepository deviceRepository;
    private final SessionService sessionService;

    public DeviceAdminController(DeviceRepository deviceRepository, SessionService sessionService) {
        this.deviceRepository = deviceRepository;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_DEVICES, session.organizationId());
        return ResponseEntity.ok(
            deviceRepository.findAll(session.organizationId()).stream().map(this::toMap).toList()
        );
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody JsonNode body) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_DEVICES, session.organizationId());

        String username = text(body, "username");
        String pinCode  = text(body, "pinCode");
        long   storeId  = body.has("storeId") ? body.get("storeId").asLong(0) : 0L;

        if (username == null || username.isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("username is required"));
        if (pinCode == null || pinCode.isBlank() || pinCode.length() != 4)
            return ResponseEntity.badRequest().body(new ErrorResponse("pinCode must be 4 digits"));
        if (storeId <= 0)
            return ResponseEntity.badRequest().body(new ErrorResponse("storeId is required"));

        long orgId = session.organizationId();
        if (deviceRepository.existsByUsername(username, orgId))
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));

        String deviceKey = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String name       = text(body, "name") != null ? text(body, "name") : username;
        String deviceType = text(body, "deviceType") != null ? text(body, "deviceType") : "KIOSK";
        boolean enabled   = !body.has("enabled") || body.get("enabled").asBoolean(true);

        long deviceId;
        try {
            deviceId = deviceRepository.create(orgId, storeId, deviceKey, name, deviceType, username, pinCode, enabled);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));
        }

        return ResponseEntity.ok(Map.of("id", deviceId, "deviceKey", deviceKey));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_DEVICES, session.organizationId());
        if (!deviceRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Device not found: " + id));
        return deviceRepository.findAll(session.organizationId()).stream()
            .filter(d -> d.id() == id)
            .findFirst()
            .map(d -> ResponseEntity.ok(toMap(d)))
            .orElse(ResponseEntity.status(404).body(null));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_DEVICES, session.organizationId());

        if (!deviceRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Device not found: " + id));

        if (body.has("pinCode")) {
            String pin = body.get("pinCode").asText("").trim();
            if (pin.length() != 4)
                return ResponseEntity.badRequest().body(new ErrorResponse("pinCode must be 4 digits"));
        }

        deviceRepository.patch(id, body);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_DEVICES, session.organizationId());

        if (!deviceRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Device not found: " + id));

        deviceRepository.delete(id);
        return ResponseEntity.ok().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(DeviceRepository.DeviceAdminView d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             d.id());
        m.put("username",       d.username());
        m.put("name",           d.name());
        m.put("deviceKey",      d.deviceKey());
        m.put("deviceType",     d.deviceType());
        m.put("organizationId", d.organizationId());
        m.put("storeId",        d.storeId());
        m.put("storeName",      d.storeName());
        m.put("enabled",        d.enabled());
        m.put("createdAt",      d.createdAt() != null ? d.createdAt().toString() : null);
        return m;
    }

    private static String text(JsonNode body, String field) {
        return body.has(field) && !body.get(field).isNull() ? body.get(field).asText(null) : null;
    }
}
