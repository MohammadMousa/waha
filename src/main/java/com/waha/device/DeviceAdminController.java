package com.waha.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waha.auth.Permission;
import com.waha.auth.PinLockoutService;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/devices")
public class DeviceAdminController {

    private final DeviceRepository deviceRepository;
    private final SessionService sessionService;
    private final BCryptPasswordEncoder encoder;
    private final PinLockoutService lockoutService;

    public DeviceAdminController(DeviceRepository deviceRepository, SessionService sessionService,
                                 BCryptPasswordEncoder encoder, PinLockoutService lockoutService) {
        this.deviceRepository = deviceRepository;
        this.sessionService   = sessionService;
        this.encoder          = encoder;
        this.lockoutService   = lockoutService;
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
        if (pinCode == null || pinCode.isBlank() || pinCode.length() != 6)
            return ResponseEntity.badRequest().body(new ErrorResponse("pinCode must be 6 digits"));
        if (isWeakPin(pinCode))
            return ResponseEntity.badRequest().body(new ErrorResponse("PIN is too easy to guess, choose a less predictable one"));
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
            deviceId = deviceRepository.create(orgId, storeId, deviceKey, name, deviceType, username, encoder.encode(pinCode), enabled);
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

        JsonNode patchBody = body;
        if (body.has("pinCode")) {
            String pin = body.get("pinCode").asText("").trim();
            if (pin.length() != 6)
                return ResponseEntity.badRequest().body(new ErrorResponse("pinCode must be 6 digits"));
            if (isWeakPin(pin))
                return ResponseEntity.badRequest().body(new ErrorResponse("PIN is too easy to guess, choose a less predictable one"));
            ObjectNode copy = body.deepCopy();
            copy.put("pinCode", encoder.encode(pin));
            patchBody = copy;
            lockoutService.clearDeviceLockout(id);
        }
        if (body.has("enabled") && body.get("enabled").asBoolean())
            lockoutService.clearDeviceLockout(id);

        deviceRepository.patch(id, patchBody);
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
        m.put("lockedUntil",    d.lockedUntil() != null ? d.lockedUntil().toString() : null);
        return m;
    }

    private static boolean isWeakPin(String pin) {
        if (pin.chars().distinct().count() == 1) return true; // 000000, 111111, …
        boolean asc = true, desc = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != 1) asc = false;
            if (pin.charAt(i - 1) - pin.charAt(i) != 1) desc = false;
        }
        if (asc || desc) return true; // 123456, 987654, …
        // repeating pairs: 112233, 445566, …
        if (pin.charAt(0) == pin.charAt(1) && pin.charAt(2) == pin.charAt(3) && pin.charAt(4) == pin.charAt(5)) return true;
        return java.util.Set.of("123123", "121212", "111222", "222333", "333444",
            "444555", "555666", "666777", "777888", "888999", "123321", "654321").contains(pin);
    }

    private static String text(JsonNode body, String field) {
        return body.has(field) && !body.get(field).isNull() ? body.get(field).asText(null) : null;
    }
}
