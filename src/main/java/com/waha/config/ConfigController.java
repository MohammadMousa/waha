package com.waha.config;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;
    private final SessionService sessionService;

    public ConfigController(ConfigService configService, SessionService sessionService) {
        this.configService = configService;
        this.sessionService = sessionService;
    }

    // Public — no auth. Returns global (org=0) properties merged with org-specific ones.
    // If orgId is provided, org-specific values override global ones on the same key.
    @GetMapping
    public ResponseEntity<?> get(@RequestParam(required = false) Long orgId) {
        return ResponseEntity.ok(configService.findAllProperties(orgId != null ? orgId : 0L));
    }

    // Admin — requires MANAGE_STORES. Updates a system property (always global, org=0).
    // Body: {"publicBaseUrl": "http://192.168.1.42:8081"}
    @PutMapping
    public ResponseEntity<?> update(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> body) {
        sessionService.requirePermission(authHeader, Permission.MANAGE_STORES, null);

        String url = body.get("publicBaseUrl");
        if (url != null) {
            configService.setPublicBaseUrl(url.trim());
        }
        return ResponseEntity.ok(configService.findAllProperties(0L));
    }
}
