package com.waha.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Admin screen calls for the sync table settings of the caller's organization (docs/sync-combined-pull.md, section 4).
@RestController
@RequestMapping("/api/admin/sync/tables")
public class SyncTableAdminController {

    private final SessionService sessionService;
    private final SyncTableConfigService config;

    public SyncTableAdminController(SessionService sessionService, SyncTableConfigService config) {
        this.sessionService = sessionService;
        this.config = config;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = require(auth);
        Map<SyncTable, SyncTableSetting> all = config.forOrganization(session.organizationId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (SyncTable t : SyncTable.values()) {
            SyncTableSetting s = all.get(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t.key());
            m.put("active", s.active());
            m.put("expirationPeriod", s.expirationPeriod());
            m.put("description", s.description() != null ? s.description() : t.defaultDescription());
            m.put("sensitive", t.sensitive());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @PutMapping("/{table}")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String table, @RequestBody(required = false) JsonNode body) {
        UserSession session = require(auth);
        SyncTable t = SyncTable.byKey(table).orElse(null);
        if (t == null) return ResponseEntity.status(404).body(new ErrorResponse("Unknown sync table: " + table));
        if (body == null || !body.isObject() || !(body.has("active") || body.has("expirationPeriod") || body.has("description"))) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Send at least one of active, expirationPeriod or description"));
        }
        Boolean active = null;
        JsonNode a = body.get("active");
        if (a != null) {
            if (!a.isBoolean()) return ResponseEntity.badRequest().body(new ErrorResponse("active must be true or false"));
            active = a.asBoolean();
        }
        boolean periodGiven = body.has("expirationPeriod");
        JsonNode p = body.get("expirationPeriod");
        if (periodGiven && p != null && !p.isNull() && !p.isTextual())
            return ResponseEntity.badRequest().body(new ErrorResponse("expirationPeriod must be text like 2H, or null"));
        boolean descGiven = body.has("description");
        JsonNode d = body.get("description");
        if (descGiven && d != null && !d.isNull() && !d.isTextual())
            return ResponseEntity.badRequest().body(new ErrorResponse("description must be text, or null"));
        try {
            config.update(session.organizationId(), t, active,
                periodGiven, p == null || p.isNull() ? null : p.asText(),
                descGiven, d == null || d.isNull() ? null : d.asText());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    private UserSession require(String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermission(auth, Permission.MANAGE_SETTINGS, session.storeId());
        return session;
    }
}
