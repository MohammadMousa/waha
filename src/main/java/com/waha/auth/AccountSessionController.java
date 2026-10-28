package com.waha.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.common.ErrorResponse;
import com.waha.device.LastSeenTracker;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

// Sessions (logins) of dashboard users and employees: list, disconnect one or several, and sign out of the others.
// Same shape as the device sessions in DeviceStatusController. The token itself is never returned; each session
// has a reference (a hash of the token) used to disconnect it.
@RestController
@RequestMapping("/api")
public class AccountSessionController {

    private final SessionService sessionService;
    private final LastSeenTracker lastSeen;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AccountSessionController(SessionService sessionService, LastSeenTracker lastSeen,
                                    NamedParameterJdbcTemplate jdbc) {
        this.sessionService = sessionService;
        this.lastSeen = lastSeen;
        this.jdbc = jdbc;
    }

    // ── admin: dashboard users ────────────────────────────────────────────────

    @GetMapping("/admin/users/{id}/sessions")
    public ResponseEntity<?> userSessions(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());
        if (!accountInOrg("users", id, session.organizationId()))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found"));
        return ResponseEntity.ok(Map.of("items", list("user_id", id, null)));
    }

    @DeleteMapping("/admin/users/{id}/sessions/{ref}")
    public ResponseEntity<?> disconnectUserSession(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id, @PathVariable String ref) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());
        if (!accountInOrg("users", id, session.organizationId()))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found"));
        return disconnect("user_id", id, List.of(ref), true);
    }

    @PostMapping("/admin/users/{id}/sessions/delete")
    public ResponseEntity<?> disconnectUserSessions(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id, @RequestBody Map<String, Object> body) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());
        if (!accountInOrg("users", id, session.organizationId()))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found"));
        return disconnectMany("user_id", id, body);
    }

    // ── admin: employees ──────────────────────────────────────────────────────

    @GetMapping("/admin/employees/{id}/sessions")
    public ResponseEntity<?> employeeSessions(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());
        if (!accountInOrg("employees", id, session.organizationId()))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found"));
        return ResponseEntity.ok(Map.of("items", list("employee_id", id, null)));
    }

    @DeleteMapping("/admin/employees/{id}/sessions/{ref}")
    public ResponseEntity<?> disconnectEmployeeSession(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id, @PathVariable String ref) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());
        if (!accountInOrg("employees", id, session.organizationId()))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found"));
        return disconnect("employee_id", id, List.of(ref), true);
    }

    @PostMapping("/admin/employees/{id}/sessions/delete")
    public ResponseEntity<?> disconnectEmployeeSessions(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id, @RequestBody Map<String, Object> body) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());
        if (!accountInOrg("employees", id, session.organizationId()))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found"));
        return disconnectMany("employee_id", id, body);
    }

    // ── own sessions (dashboard user or employee) ─────────────────────────────

    @GetMapping("/auth/me/sessions")
    public ResponseEntity<?> mySessions(@RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        String column = ownColumn(session);
        if (column == null) return deviceNotSupported();
        return ResponseEntity.ok(Map.of("items", list(column, ownId(session), session.token())));
    }

    @DeleteMapping("/auth/me/sessions/{ref}")
    public ResponseEntity<?> disconnectMine(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String ref) {
        UserSession session = sessionService.requireSession(auth);
        String column = ownColumn(session);
        if (column == null) return deviceNotSupported();
        return disconnect(column, ownId(session), List.of(ref), true);
    }

    // Ends every other session of the caller; this one stays signed in.
    @PostMapping("/auth/me/sessions/sign-out-others")
    public ResponseEntity<?> signOutOthers(@RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        String column = ownColumn(session);
        if (column == null) return deviceNotSupported();
        int n = session.isEmployeeSession()
            ? sessionService.endOtherEmployeeSessions(session.employeeId(), session.token())
            : sessionService.endUserSessions(session.userId(), session.token());
        return ResponseEntity.ok(Map.of("signedOut", n));
    }

    // ── shared ────────────────────────────────────────────────────────────────

    private static String ownColumn(UserSession s) {
        if (s.isEmployeeSession()) return "employee_id";
        if (s.isUserSession()) return "user_id";
        return null;
    }

    private static long ownId(UserSession s) {
        return s.isEmployeeSession() ? s.employeeId() : s.userId();
    }

    private static ResponseEntity<?> deviceNotSupported() {
        return ResponseEntity.badRequest().body(new ErrorResponse("Device sessions are managed from the device admin"));
    }

    // table is a fixed name chosen above, never client input.
    private boolean accountInOrg(String table, long id, long orgId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE id = :id AND organization_id = :org",
            Map.of("id", id, "org", orgId), Integer.class);
        return n != null && n > 0;
    }

    // column is a fixed name chosen above, never client input. currentToken marks the caller's own session.
    private List<Map<String, Object>> list(String column, long id, String currentToken) {
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT us.token, SHA2(us.token, 256) AS ref, us.created_at, us.last_seen_at, us.app_version, "
                + "us.mode, us.store_id, s.name AS store_name, us.properties "
                + "FROM user_sessions us LEFT JOIN stores s ON s.id = us.store_id WHERE us." + column + " = :id",
            Map.of("id", id), (rs, i) -> {
                Timestamp created = rs.getTimestamp("created_at");
                Timestamp dbSeen = rs.getTimestamp("last_seen_at");
                Instant seen = dbSeen != null ? dbSeen.toInstant() : null;
                String version = rs.getString("app_version");
                String token = rs.getString("token");
                var mem = lastSeen.getSession(token);
                if (mem.isPresent()) {
                    if (seen == null || mem.get().at().isAfter(seen)) seen = mem.get().at();
                    if (mem.get().appVersion() != null) version = mem.get().appVersion();
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ref", rs.getString("ref"));
                m.put("createdAt", created != null ? created.toInstant().toString() : null);
                m.put("lastSeenAt", seen != null ? seen.toString() : null);
                m.put("appVersion", version);
                m.put("mode", rs.getString("mode"));
                long sid = rs.getLong("store_id");
                m.put("storeId", rs.wasNull() ? null : sid);
                m.put("storeName", rs.getString("store_name"));
                m.put("info", parseInfo(rs.getString("properties")));
                if (currentToken != null) m.put("current", token.equals(currentToken));
                return m;
            });
        // Newest activity first (a session never seen sorts by creation time).
        rows.sort((a, b) -> String.valueOf(b.get("lastSeenAt") != null ? b.get("lastSeenAt") : b.get("createdAt"))
            .compareTo(String.valueOf(a.get("lastSeenAt") != null ? a.get("lastSeenAt") : a.get("createdAt"))));
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("latest", i == 0);
        return rows;
    }

    private Map<String, Object> parseInfo(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<?> disconnect(String column, long id, List<String> refs, boolean single) {
        int n = end(column, id, refs);
        if (single && n == 0) return ResponseEntity.status(404).body(new ErrorResponse("Session not found"));
        return ResponseEntity.ok(Map.of("disconnected", n));
    }

    private ResponseEntity<?> disconnectMany(String column, long id, Map<String, Object> body) {
        if (!(body.get("refs") instanceof List<?> raw) || raw.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("refs must be a non-empty list"));
        if (raw.size() > 200)
            return ResponseEntity.badRequest().body(new ErrorResponse("At most 200 sessions per request"));
        List<String> refs = raw.stream().filter(Objects::nonNull).map(Object::toString).distinct().toList();
        return disconnect(column, id, refs, false);
    }

    // Unknown references, or ones of another account, are skipped.
    private int end(String column, long id, List<String> refs) {
        List<String> tokens = jdbc.queryForList(
            "SELECT token FROM user_sessions WHERE " + column + " = :id AND SHA2(token, 256) IN (:refs)",
            Map.of("id", id, "refs", refs), String.class);
        for (String token : tokens) sessionService.deleteSession(token);
        return tokens.size();
    }
}
