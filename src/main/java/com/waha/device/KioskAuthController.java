package com.waha.device;

import com.waha.auth.Permission;
import com.waha.auth.PinLockoutService;
import com.waha.auth.Role;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/kiosk/auth")
public class KioskAuthController {

    // Devices have inherent KIOSK permissions — no employee_roles lookup needed.
    private static final Set<String> KIOSK_PERMISSIONS = Permission.BY_ROLE
        .getOrDefault(Role.KIOSK, Set.of())
        .stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private record VerifyRecord(int failCount, Instant windowStart, Instant cooldownUntil) {}
    private final ConcurrentHashMap<Long, VerifyRecord> verifyMap = new ConcurrentHashMap<>();
    private static final int      VERIFY_MAX_FAILS = 5;
    private static final Duration VERIFY_WINDOW    = Duration.ofMinutes(5);
    private static final Duration VERIFY_COOLDOWN  = Duration.ofMinutes(5);

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

    @PostMapping("/pin/verify")
    public ResponseEntity<?> verifyPin(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {
        var session = sessionService.requireSession(authHeader);
        long deviceId = session.deviceId();

        Instant now = Instant.now();
        VerifyRecord rec = verifyMap.get(deviceId);
        if (rec != null && rec.cooldownUntil() != null && now.isBefore(rec.cooldownUntil())) {
            long secs = Duration.between(now, rec.cooldownUntil()).toSeconds();
            return ResponseEntity.status(429).body(
                new ErrorResponse("Too many wrong PINs — try again in " + ((secs / 60) + 1) + " minute(s)"));
        }

        var auth = deviceRepository.findAuthById(deviceId);
        if (auth.isEmpty() || !auth.get().enabled())
            return ResponseEntity.status(401).body(new ErrorResponse("Device not available"));

        String pinCode = body.getOrDefault("pinCode", "").toString().trim();
        if (pinCode.length() != 6 || !pinCode.chars().allMatch(Character::isDigit))
            return ResponseEntity.badRequest().body(new ErrorResponse("PIN must be exactly 6 digits"));

        boolean matches = encoder.matches(pinCode, auth.get().pinCode());
        if (matches) {
            verifyMap.remove(deviceId);
            return ResponseEntity.ok(Map.of("valid", true));
        }

        verifyMap.compute(deviceId, (id, existing) -> {
            if (existing == null || existing.cooldownUntil() != null
                    || Duration.between(existing.windowStart(), Instant.now()).compareTo(VERIFY_WINDOW) > 0)
                existing = new VerifyRecord(0, Instant.now(), null);
            int count = existing.failCount() + 1;
            if (count >= VERIFY_MAX_FAILS)
                return new VerifyRecord(count, existing.windowStart(), Instant.now().plus(VERIFY_COOLDOWN));
            return new VerifyRecord(count, existing.windowStart(), null);
        });

        return ResponseEntity.ok(Map.of("valid", false));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = authHeader == null ? null : authHeader.replaceFirst("(?i)^Bearer ", "").trim();
        if (token != null && !token.isBlank()) sessionService.deleteSession(token);
        return ResponseEntity.ok().build();
    }
}
