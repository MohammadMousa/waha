package com.waha.employee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
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

    public PosAuthController(EmployeeRepository employeeRepository, SessionService sessionService, ObjectMapper objectMapper) {
        this.employeeRepository = employeeRepository;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
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
        if (auth.isEmpty() || !auth.get().pinCode().equals(pinCode))
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid username or PIN"));

        if (!auth.get().enabled())
            return ResponseEntity.status(401).body(new ErrorResponse("Account is disabled"));

        long employeeId = auth.get().id();
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
