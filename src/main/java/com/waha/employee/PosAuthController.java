package com.waha.employee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.auth.PinLockoutService;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
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
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String username = body.getOrDefault("username", "").toString().trim();
        String pinCode  = body.getOrDefault("pinCode",  "").toString().trim();
        long   orgId    = body.containsKey("organizationId")
            ? Long.parseLong(body.get("organizationId").toString()) : 1L;

        if (username.isEmpty() || pinCode.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("username and pinCode are required"));

        var auth = employeeRepository.findAuthRecord(username, orgId);

        long employeeId = auth.map(EmployeeRepository.EmployeeAuth::id).orElse(-1L);
        if (employeeId > 0 && lockoutService.isEmployeeLocked(employeeId))
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid credentials or account temporarily locked"));

        boolean validPin = auth.isPresent() && encoder.matches(pinCode, auth.get().pinCode());
        if (!validPin) {
            if (employeeId > 0) lockoutService.recordEmployeeFailure(employeeId);
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid credentials or account temporarily locked"));
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

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = authHeader == null ? null : authHeader.replaceFirst("(?i)^Bearer ", "").trim();
        if (token != null && !token.isBlank()) sessionService.deleteSession(token);
        return ResponseEntity.ok().build();
    }
}
