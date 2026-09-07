package com.waha.employee;

import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/pos/auth")
public class PosAuthController {

    private final EmployeeRepository employeeRepository;
    private final SessionService sessionService;

    public PosAuthController(EmployeeRepository employeeRepository, SessionService sessionService) {
        this.employeeRepository = employeeRepository;
        this.sessionService = sessionService;
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

        Long storeId = employeeRepository.findPrimaryStoreId(employeeId).orElse(null);
        if (storeId != null) sessionService.setStore(token, storeId);

        Set<String> permissions = storeId != null
            ? employeeRepository.resolvePermissions(employeeId, storeId)
            : Set.of();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token",          token);
        resp.put("employeeId",     employeeId);
        resp.put("organizationId", auth.get().organizationId());
        resp.put("storeId",        storeId);
        resp.put("mode",           "NORMAL");
        resp.put("permissions",    permissions);
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
