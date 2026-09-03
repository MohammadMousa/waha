package com.waha.integration;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/integrations")
public class IntegrationsController {

    private final SessionService sessionService;
    private final IntegrationsAdminRepository integrationsAdminRepository;

    public IntegrationsController(SessionService sessionService,
                                  IntegrationsAdminRepository integrationsAdminRepository) {
        this.sessionService = sessionService;
        this.integrationsAdminRepository = integrationsAdminRepository;
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String  entityType,
            @RequestParam(required = false) String  status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        sessionService.requirePermission(auth, Permission.MANAGE_SYSTEM, 1L);
        if (size < 1 || size > 200) size = 20;

        long total = integrationsAdminRepository.countLogs(entityType, status);
        List<Map<String, Object>> items = integrationsAdminRepository.getLogs(entityType, status, page, size);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items",      items);
        out.put("totalCount", total);
        out.put("page",       page);
        out.put("size",       size);
        return ResponseEntity.ok(out);
    }
}
