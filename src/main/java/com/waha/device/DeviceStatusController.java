package com.waha.device;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.config.ConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

// Kiosk heartbeat and the admin device-status report.
@RestController
@RequestMapping("/api")
public class DeviceStatusController {

    private final SessionService sessionService;
    private final LastSeenTracker lastSeen;
    private final ConfigService configService;
    private final NamedParameterJdbcTemplate jdbc;

    public DeviceStatusController(SessionService sessionService, LastSeenTracker lastSeen,
                                  ConfigService configService, NamedParameterJdbcTemplate jdbc) {
        this.sessionService = sessionService;
        this.lastSeen = lastSeen;
        this.configService = configService;
        this.jdbc = jdbc;
    }

    // Kiosk: "I am alive". Body is optional: { "appVersion": "0.1.31" }
    @PostMapping("/kiosk/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        UserSession session = sessionService.requireSession(auth);
        if (!session.isDeviceSession())
            return ResponseEntity.status(403).body(new ErrorResponse("Only kiosk devices send heartbeats"));
        String version = KioskAuthController.appVersion(body != null ? body.get("appVersion") : null);
        lastSeen.touch(session.token(), session.deviceId(), version);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // Admin: one row per device with online state, last signal, app version and last order.
    @GetMapping("/admin/device-status")
    public ResponseEntity<?> status(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_DEVICES, session.organizationId());
        long orgId = session.organizationId();

        int heartbeat = 0;
        try {
            heartbeat = Integer.parseInt(configService.findAllProperties(orgId)
                .getOrDefault("heartbeat_minutes", "0").trim());
        } catch (NumberFormatException ignored) { }
        final int hb = heartbeat;
        boolean monitoring = hb > 0;
        Instant now = Instant.now();

        List<Map<String, Object>> rows = jdbc.query("""
            SELECT d.id, d.username, d.name, d.device_type, d.enabled, d.store_id, s.name AS store_name,
                   d.last_seen_at, d.app_version,
                   (SELECT o.created_at FROM orders o WHERE o.username = d.username
                      AND o.organization_id = d.organization_id ORDER BY o.created_at DESC LIMIT 1) AS last_order_at,
                   (SELECT o.status FROM orders o WHERE o.username = d.username
                      AND o.organization_id = d.organization_id ORDER BY o.created_at DESC LIMIT 1) AS last_order_status
            FROM devices d JOIN stores s ON s.id = d.store_id
            WHERE d.organization_id = :org
            ORDER BY d.name
            """, Map.of("org", orgId), (rs, i) -> {
                long id = rs.getLong("id");
                Timestamp dbSeen = rs.getTimestamp("last_seen_at");
                Instant seen = dbSeen != null ? dbSeen.toInstant() : null;
                String version = rs.getString("app_version");
                var mem = lastSeen.get(id);
                if (mem.isPresent()) {
                    if (seen == null || mem.get().at().isAfter(seen)) seen = mem.get().at();
                    if (mem.get().appVersion() != null) version = mem.get().appVersion();
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",          id);
                m.put("username",    rs.getString("username"));
                m.put("name",        rs.getString("name"));
                m.put("deviceType",  rs.getString("device_type"));
                m.put("enabled",     rs.getBoolean("enabled"));
                m.put("storeId",     rs.getLong("store_id"));
                m.put("storeName",   rs.getString("store_name"));
                m.put("lastSeenAt",  seen != null ? seen.toString() : null);
                m.put("appVersion",  version);
                // online: true / false, or null when monitoring is off or the device was never seen
                Boolean online = null;
                Long offlineMinutes = null;
                if (monitoring && seen != null) {
                    long ageSec = Duration.between(seen, now).getSeconds();
                    online = ageSec <= hb * 90L;   // 1.5 x heartbeat
                    offlineMinutes = online ? 0L : ageSec / 60;
                }
                m.put("online",         online);
                m.put("offlineMinutes", offlineMinutes);
                Timestamp lo = rs.getTimestamp("last_order_at");
                m.put("lastOrderAt",     lo != null ? lo.toInstant().toString() : null);
                m.put("lastOrderStatus", rs.getString("last_order_status"));
                return m;
            });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heartbeatMinutes", heartbeat);
        out.put("monitoring",       monitoring);
        out.put("serverTime",       now.toString());
        out.put("items",            rows);
        return ResponseEntity.ok(out);
    }
}
