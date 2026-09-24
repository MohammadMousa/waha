package com.waha.device;

import com.waha.auth.Permission;
import com.waha.auth.PinLockoutService;
import com.waha.auth.Role;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/kiosk/auth")
public class KioskAuthController {

    // Devices have inherent KIOSK permissions — no employee_roles lookup needed.
    private static final Set<String> KIOSK_PERMISSIONS = Permission.BY_ROLE
        .getOrDefault(Role.KIOSK, Set.of())
        .stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final DeviceRepository deviceRepository;
    private final SessionService sessionService;
    private final BCryptPasswordEncoder encoder;
    private final PinLockoutService lockoutService;

    public KioskAuthController(DeviceRepository deviceRepository, SessionService sessionService,
                               BCryptPasswordEncoder encoder, PinLockoutService lockoutService) {
        this.deviceRepository = deviceRepository;
        this.sessionService   = sessionService;
        this.encoder          = encoder;
        this.lockoutService   = lockoutService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String username = body.getOrDefault("username", "").toString().trim();
        String pinCode  = body.getOrDefault("pinCode",  "").toString().trim();
        long   orgId    = body.containsKey("organizationId")
            ? Long.parseLong(body.get("organizationId").toString()) : 1L;

        if (username.isEmpty() || pinCode.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("username and pinCode are required"));

        var auth = deviceRepository.findAuthRecord(username, orgId);

        long deviceId = auth.map(DeviceRepository.DeviceAuth::id).orElse(-1L);
        if (deviceId > 0 && lockoutService.isDeviceLocked(deviceId))
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid credentials or account temporarily locked"));

        boolean validPin = auth.isPresent() && encoder.matches(pinCode, auth.get().pinCode());
        if (!validPin) {
            if (deviceId > 0) lockoutService.recordDeviceFailure(deviceId);
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid credentials or account temporarily locked"));
        }

        if (!auth.get().enabled())
            return ResponseEntity.status(401).body(new ErrorResponse("Device is disabled"));

        lockoutService.recordDeviceSuccess(deviceId);
        long storeId  = auth.get().storeId();

        String token = sessionService.createDeviceSession(deviceId);
        sessionService.setStore(token, storeId);
        sessionService.setMode(token, "KIOSK");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token",          token);
        resp.put("deviceId",       deviceId);
        resp.put("organizationId", auth.get().organizationId());
        resp.put("storeId",        storeId);
        resp.put("mode",           "KIOSK");
        resp.put("permissions",    KIOSK_PERMISSIONS);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        var session = sessionService.requireSession(authHeader);
        var device  = deviceRepository.findById(session.deviceId());
        if (device.isEmpty())
            return ResponseEntity.status(401).body(new ErrorResponse("Device not found"));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deviceId",       session.deviceId());
        resp.put("organizationId", session.organizationId());
        resp.put("storeId",        session.storeId());
        resp.put("mode",           "KIOSK");
        resp.put("permissions",    KIOSK_PERMISSIONS);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = authHeader == null ? null : authHeader.replaceFirst("(?i)^Bearer ", "").trim();
        if (token != null && !token.isBlank()) sessionService.deleteSession(token);
        return ResponseEntity.ok().build();
    }
}
