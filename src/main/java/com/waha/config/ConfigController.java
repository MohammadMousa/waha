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

    // Public — no auth. Returns system_properties as key→value map.
    @GetMapping
    public ResponseEntity<?> get() {
        return ResponseEntity.ok(configService.findAllProperties());
    }

    // Admin — requires MANAGE_STORES. Updates a system property.
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
        return ResponseEntity.ok(configService.findAllProperties());
    }
}
