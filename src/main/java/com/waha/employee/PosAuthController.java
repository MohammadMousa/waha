package com.waha.employee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.auth.PinLockoutService;
import com.waha.auth.SessionService;
import com.waha.common.AccountLockedError;
import com.waha.common.ErrorResponse;
import com.waha.common.InvalidCredentialsError;
import com.waha.common.RateLimitedError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/pos/auth")
public class PosAuthController {

    private final EmployeeRepository employeeRepository;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder encoder;
    private final PinLockoutService lockoutService;

    public PosAuthController(EmployeeRepository employeeRepository, SessionService sessionService,
                             ObjectMapper objectMapper, BCryptPasswordEncoder encoder,
                             PinLockoutService lockoutService) {
        this.employeeRepository = employeeRepository;
        this.sessionService     = sessionService;
        this.objectMapper       = objectMapper;
        this.encoder            = encoder;
        this.lockoutService     = lockoutService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String ip = clientIp(req);
        if (lockoutService.isIpLocked(ip)) {
            long secs = lockoutService.getIpLockSeconds(ip);
            return ResponseEntity.status(429).header("Retry-After", String.valueOf(secs)).body(RateLimitedError.of(secs));
        }

        String username = body.getOrDefault("username", "").toString().trim();
        String pinCode  = body.getOrDefault("pinCode",  "").toString().trim();
        long   orgId    = body.containsKey("organizationId")
            ? Long.parseLong(body.get("organizationId").toString()) : 1L;

        if (username.isEmpty() || pinCode.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("username and pinCode are required"));

        var auth = employeeRepository.findAuthRecord(username, orgId);

        long employeeId = auth.map(EmployeeRepository.EmployeeAuth::id).orElse(-1L);
        String unknownKey = orgId + ":" + username.toLowerCase();

        if (employeeId > 0) {
            if (lockoutService.isEmployeeLocked(employeeId)) {
                long secs = lockoutService.getEmployeeLockSeconds(employeeId);
                return ResponseEntity.status(429).header("Retry-After", String.valueOf(secs)).body(AccountLockedError.of(secs));
            }
        } else {
            if (lockoutService.isUnknownLocked(unknownKey)) {
                long secs = lockoutService.getUnknownLockSeconds(unknownKey);
                return ResponseEntity.status(429).header("Retry-After", String.valueOf(secs)).body(AccountLockedError.of(secs));
            }
        }

        boolean validPin = auth.isPresent() && encoder.matches(pinCode, auth.get().pinCode());
        if (!validPin) {
            lockoutService.recordIpFailure(ip);
            int remaining;
            long lockSecs;
            if (employeeId > 0) {
                remaining = lockoutService.recordEmployeeFailure(employeeId);
                lockSecs  = lockoutService.getEmployeeLockSeconds(employeeId);
            } else {
                remaining = lockoutService.recordUnknownFailure(unknownKey);
                lockSecs  = lockoutService.getUnknownLockSeconds(unknownKey);
            }
            if (remaining == 0)
                return ResponseEntity.status(429).header("Retry-After", String.valueOf(lockSecs)).body(AccountLockedError.of(lockSecs));
            return ResponseEntity.status(401).body(InvalidCredentialsError.of(remaining));
        }

        if (!auth.get().enabled())
            return ResponseEntity.status(401).body(new ErrorResponse("Account is disabled"));

        lockoutService.recordEmployeeSuccess(employeeId);
        String token = sessionService.createEmployeeSession(employeeId);

        Set<String> permissions = sessionService.resolveEmployeePermissionsUnified(employeeId);
        Map<String, Object> profile = employeeRepository.findProfileForSession(employeeId);

        List<Map<String, Object>> storesMapped = employeeRepository.findStores(employeeId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          s.get("id"));
            m.put("name",        s.get("name"));
            m.put("displayName", parseJsonName((String) s.get("display_name")));
            return m;
        }).toList();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token",          token);
        resp.put("employeeId",     employeeId);
        resp.put("organizationId", auth.get().organizationId());
        resp.put("employeeName",   profile.getOrDefault("employeeName", null));
        resp.put("roleName",       profile.getOrDefault("roleName", null));
        resp.put("permissions",    permissions);
        resp.put("stores",         storesMapped);
        return ResponseEntity.ok(resp);
    }

    private Object parseJsonName(String json) {
        if (json == null) return null;
        try { return objectMapper.readValue(json, Object.class); }
        catch (Exception e) { return json; }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = authHeader == null ? null : authHeader.replaceFirst("(?i)^Bearer ", "").trim();
        if (token != null && !token.isBlank()) sessionService.deleteSession(token);
        return ResponseEntity.ok().build();
    }
}
