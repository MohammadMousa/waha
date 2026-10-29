package com.waha.auth;

import com.waha.common.ForbiddenException;
import com.waha.common.InvalidRequestException;
import com.waha.common.OrganizationRequiredException;
import com.waha.common.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int DEFAULT_ACCOUNT_SESSIONS_CAP = 5;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final com.waha.device.LastSeenTracker lastSeen;
    private final SecureRandom random = new SecureRandom();

    // Tokens stay valid until explicit logout, revocation or account/session invalidation (Waha Rules: Auto-login).
    private static final Instant NO_EXPIRY = Instant.parse("9999-12-31T00:00:00Z");

    public SessionService(NamedParameterJdbcTemplate namedJdbc, UserRepository userRepository,
                          RoleRepository roleRepository, com.waha.device.LastSeenTracker lastSeen) {
        this.lastSeen = lastSeen;
        this.namedJdbc = namedJdbc;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public String createSession(long userId) {
        String token = generateToken();
        Instant expiresAt = NO_EXPIRY;
        Map<String, Object> params = new HashMap<>();
        params.put("token", token);
        params.put("userId", userId);
        params.put("expiresAt", Timestamp.from(expiresAt));
        namedJdbc.update(
            "INSERT INTO user_sessions (token, user_id, expires_at) VALUES (:token, :userId, :expiresAt)",
            params
        );
        enforceAccountCap("user_id", userId, token);
        return token;
    }

    public String createEmployeeSession(long employeeId) {
        String token = generateToken();
        Instant expiresAt = NO_EXPIRY;
        namedJdbc.update(
            "INSERT INTO user_sessions (token, employee_id, expires_at) VALUES (:token, :eid, :exp)",
            Map.of("token", token, "eid", employeeId, "exp", Timestamp.from(expiresAt))
        );
        enforceAccountCap("employee_id", employeeId, token);
        return token;
    }

    public String createDeviceSession(long deviceId) {
        String token = generateToken();
        Instant expiresAt = NO_EXPIRY;
        namedJdbc.update(
            "INSERT INTO user_sessions (token, device_id, expires_at) VALUES (:token, :did, :exp)",
            Map.of("token", token, "did", deviceId, "exp", Timestamp.from(expiresAt))
        );
        return token;
    }

    public UserSession requireSession(String authHeader) {
        String token = authHeader == null ? null : authHeader.replaceFirst("(?i)^Bearer ", "").trim();
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Missing or invalid session");
        }

        List<UserSession> results = namedJdbc.query(
            "SELECT us.token, us.user_id, us.employee_id, us.device_id, us.store_id, us.mode, " +
            "       COALESCE(u.organization_id, e.organization_id, d.organization_id) AS organization_id " +
            "FROM user_sessions us " +
            "LEFT JOIN users u      ON u.id = us.user_id " +
            "LEFT JOIN employees e  ON e.id = us.employee_id " +
            "LEFT JOIN devices d    ON d.id = us.device_id " +
            "WHERE us.token = :token AND us.expires_at > NOW() " +
            // Tokens never expire, so a disabled device or employee must be refused on every request.
            "  AND (us.device_id IS NULL OR d.enabled = 1) " +
            "  AND (us.employee_id IS NULL OR e.enabled = 1)",
            Map.of("token", token),
            (rs, i) -> {
                long storeId = rs.getLong("store_id");
                Long storeIdBoxed = rs.wasNull() ? null : storeId;
                long eid = rs.getLong("employee_id"); Long employeeId = rs.wasNull() ? null : eid;
                long did = rs.getLong("device_id");   Long deviceId   = rs.wasNull() ? null : did;
                return new UserSession(
                    rs.getString("token"), rs.getLong("user_id"),
                    employeeId, deviceId,
                    storeIdBoxed, rs.getString("mode"),
                    rs.getLong("organization_id")
                );
            }
        );

        UserSession found = results.stream().findFirst()
            .orElseThrow(() -> new UnauthorizedException("Missing or invalid session"));
        lastSeen.touch(found.token(), found.deviceId() != null ? found.deviceId() : 0L, null);
        return applyOrganizationScope(found);
    }

    // A super-admin (organization 0) works inside one organization by sending X-Organization-Id on each call; the
    // session then acts as that organization and every scoped call follows. Nothing is stored on the session.
    // Without the header a super-admin cannot write (reads just return empty lists).
    private UserSession applyOrganizationScope(UserSession s) {
        if (!s.isUserSession()) return s;
        HttpServletRequest req = currentRequest();
        if (req == null) return s;
        Long wanted = OrganizationScope.parse(req.getHeader(OrganizationScope.HEADER));
        if (wanted != null && wanted != s.organizationId()) {
            if (!resolvePermissions(s.userId(), null).contains(Permission.MANAGE_SYSTEM.name()))
                throw new ForbiddenException("Organization not available");
            Integer n = namedJdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE id = :id",
                Map.of("id", wanted), Integer.class);
            if (n == null || n == 0) throw new InvalidRequestException("Unknown organization: " + wanted);
            return new UserSession(s.token(), s.userId(), s.employeeId(), s.deviceId(), s.storeId(), s.mode(), wanted);
        }
        if (s.organizationId() == 0 && OrganizationScope.writeNeedsOrganization(req.getMethod(), req.getRequestURI()))
            throw new OrganizationRequiredException();
        return s;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    public void setStore(String token, long storeId) {
        namedJdbc.update(
            "UPDATE user_sessions SET store_id = :storeId WHERE token = :token",
            Map.of("storeId", storeId, "token", token)
        );
    }

    public void setMode(String token, String mode) {
        namedJdbc.update(
            "UPDATE user_sessions SET mode = :mode WHERE token = :token",
            Map.of("mode", mode, "token", token)
        );
    }

    public void deleteSession(String token) {
        lastSeen.forget(token);
        namedJdbc.update("DELETE FROM user_sessions WHERE token = :token", Map.of("token", token));
    }

    // Revocation: tokens never expire, so these are how access ends (disable, PIN/password change,
    // sign-out, delete). Each returns how many tokens were ended.
    public int endDeviceSessions(long deviceId) {
        lastSeen.forgetDevice(deviceId);
        return namedJdbc.update("DELETE FROM user_sessions WHERE device_id = :id", Map.of("id", deviceId));
    }

    public int endEmployeeSessions(long employeeId) {
        return deleteSessionsWhere("employee_id = :id", Map.of("id", employeeId));
    }

    // exceptToken keeps the caller's own session, e.g. when users change their own password.
    public int endUserSessions(long userId, String exceptToken) {
        return deleteSessionsWhere(
            "user_id = :id AND device_id IS NULL AND employee_id IS NULL" + (exceptToken != null ? " AND token <> :keep" : ""),
            exceptToken != null ? Map.of("id", userId, "keep", exceptToken) : Map.of("id", userId));
    }

    // Every other session of the same employee, keeping the caller's own.
    public int endOtherEmployeeSessions(long employeeId, String exceptToken) {
        return deleteSessionsWhere("employee_id = :id AND token <> :keep", Map.of("id", employeeId, "keep", exceptToken));
    }

    private int deleteSessionsWhere(String condition, Map<String, Object> params) {
        List<String> tokens = namedJdbc.queryForList("SELECT token FROM user_sessions WHERE " + condition, params, String.class);
        tokens.forEach(lastSeen::forget);
        return namedJdbc.update("DELETE FROM user_sessions WHERE " + condition, params);
    }

    // Login: the app version is written straight away (with the first last-seen time) for dashboard and employee sessions.
    public void saveAppVersionAtLogin(String token, Object appVersion) {
        String v = SessionInfo.appVersion(appVersion);
        if (v != null) lastSeen.touchAndSave(token, 0L, v);
    }

    // Token check with the optional X-App-Version header: memory only, written to the database in batches.
    public void noteAppVersion(UserSession session, String appVersion) {
        String v = SessionInfo.appVersion(appVersion);
        if (v != null) lastSeen.touch(session.token(), session.deviceId() != null ? session.deviceId() : 0L, v);
    }

    // What a login tells us about the client (OS, model, browser). Stored once, at login; never fails the login.
    public void saveLoginInfo(String token, Object sessionProperties, String userAgent) {
        try {
            String json = SessionInfo.toJson(sessionProperties, userAgent);
            if (json != null) {
                namedJdbc.update("UPDATE user_sessions SET properties = :p WHERE token = :t", Map.of("p", json, "t", token));
            }
        } catch (RuntimeException e) {
            log.warn("Could not save login info for a session: {}", e.getMessage());
        }
    }

    // After a dashboard or employee login: if the account has more sessions than Account_Sessions_Cap,
    // the ones with the oldest activity are ended. The new session is never one of them. Never fails the login.
    private void enforceAccountCap(String column, long accountId, String newToken) {
        try {
            int cap = accountSessionsCap();
            if (cap <= 0) return;
            List<SessionInfo.Candidate> all = namedJdbc.query(
                "SELECT token, created_at, last_seen_at FROM user_sessions WHERE " + column + " = :id",
                Map.of("id", accountId), (rs, i) -> {
                    String token = rs.getString("token");
                    Timestamp created = rs.getTimestamp("created_at");
                    Timestamp seen = rs.getTimestamp("last_seen_at");
                    Instant lastSeenAt = seen != null ? seen.toInstant() : null;
                    var mem = lastSeen.getSession(token);
                    if (mem.isPresent() && (lastSeenAt == null || mem.get().at().isAfter(lastSeenAt))) lastSeenAt = mem.get().at();
                    return new SessionInfo.Candidate(token, created != null ? created.toInstant() : null, lastSeenAt);
                });
            List<String> remove = SessionInfo.overCap(all, cap, newToken);
            for (String token : remove) deleteSession(token);
            if (!remove.isEmpty()) log.info("Account session cap ({}) ended {} old session(s) of {} {}", cap, remove.size(), column, accountId);
        } catch (RuntimeException e) {
            log.warn("Account session cap skipped: {}", e.getMessage());
        }
    }

    // Global (organization 0) property Account_Sessions_Cap, default 5; 0 turns the cap off.
    private int accountSessionsCap() {
        try {
            String v = namedJdbc.query(
                "SELECT value FROM system_properties WHERE `key` = 'Account_Sessions_Cap' AND organization_id = 0 LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
            if (v != null) {
                int n = Integer.parseInt(v.trim());
                if (n >= 0 && n <= 1000) return n;
            }
        } catch (RuntimeException ignored) { }
        return DEFAULT_ACCOUNT_SESSIONS_CAP;
    }

    // A device moved to another branch keeps working; its open tokens follow it to the new branch.
    public void moveDeviceSessions(long deviceId, long storeId) {
        namedJdbc.update("UPDATE user_sessions SET store_id = :sid WHERE device_id = :id",
            Map.of("sid", storeId, "id", deviceId));
    }

    public Optional<UserSession> tryResolveSession(String authHeader) {
        try {
            return Optional.of(requireSession(authHeader));
        } catch (UnauthorizedException e) {
            return Optional.empty();
        }
    }

    public Long resolveStoreId(Long explicit, String authHeader) {
        if (explicit != null) return explicit;
        return tryResolveSession(authHeader).map(UserSession::storeId).orElse(null);
    }

    // Returns the union of permissions from all roles that cover the given store via hierarchy.
    // storeId=null → only SYSTEM-scope roles match (platform-level operations).
    public Set<String> resolvePermissions(long userId, Long storeId) {
        return roleRepository.resolvePermissions(userId, storeId);
    }

    // Returns the union of permissions for org-level operations (no specific store in scope).
    public Set<String> resolvePermissionsForOrg(long userId, long orgId) {
        return roleRepository.resolvePermissionsForOrg(userId, orgId);
    }

    // Resolves permissions for an employee without a specific store context —
    // unions all roles the employee has across all branches.
    public Set<String> resolveEmployeePermissionsUnified(long employeeId) {
        return namedJdbc.query(
            "SELECT DISTINCT r.name FROM employee_roles er JOIN roles r ON r.id = er.role_id WHERE er.employee_id = :eid",
            Map.of("eid", employeeId),
            (rs, i) -> rs.getString("name")
        ).stream()
         .flatMap(name -> {
             try { return Permission.BY_ROLE.getOrDefault(Role.valueOf(name), Set.of()).stream(); }
             catch (IllegalArgumentException e) { return java.util.stream.Stream.empty(); }
         })
         .map(Enum::name)
         .collect(java.util.stream.Collectors.toSet());
    }

    public void requirePermission(String authHeader, Permission permission, Long storeId) {
        UserSession session = requireSession(authHeader);
        Set<String> perms = resolveSessionPermissions(session, storeId);
        if (!perms.contains(permission.name())) {
            throw new ForbiddenException("Permission denied: " + permission.name());
        }
    }

    // Resolves permissions for any session type (user, employee, or device).
    public Set<String> resolveSessionPermissions(UserSession session, Long storeId) {
        if (session.isEmployeeSession() && storeId != null) {
            return namedJdbc.query(
                "SELECT r.name FROM employee_roles er JOIN roles r ON r.id = er.role_id " +
                "WHERE er.employee_id = :eid " +
                "AND ((er.scope_type = 'BRANCH' AND er.scope_id = :sid) " +
                "  OR (er.scope_type = 'BRANCH_GROUP' AND er.scope_id = " +
                "      (SELECT branch_group_id FROM stores WHERE id = :sid)))",
                Map.of("eid", session.employeeId(), "sid", storeId),
                (rs, i) -> rs.getString("name")
            ).stream()
             .flatMap(name -> { try { return Permission.BY_ROLE.getOrDefault(Role.valueOf(name), Set.of()).stream(); } catch (IllegalArgumentException e) { return java.util.stream.Stream.empty(); } })
             .map(Enum::name)
             .collect(java.util.stream.Collectors.toSet());
        }
        if (session.isDeviceSession()) {
            return Permission.BY_ROLE.getOrDefault(Role.KIOSK, Set.of())
                .stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return resolvePermissions(session.userId(), storeId);
    }

    public void requirePermissionForOrg(String authHeader, Permission permission, long orgId) {
        UserSession session = requireSession(authHeader);
        requirePermissionForOrg(session, permission, orgId);
    }

    public void requirePermissionForOrg(UserSession session, Permission permission, long orgId) {
        Set<String> perms = resolvePermissionsForOrg(session.userId(), orgId);
        if (!perms.contains(permission.name())) {
            throw new ForbiddenException("Permission denied: " + permission.name());
        }
    }

    public String resolveUsername(String explicit, String authHeader) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return tryResolveSession(authHeader).flatMap(session -> {
            if (session.isUserSession())
                return userRepository.findById(session.userId()).map(User::username);
            if (session.isEmployeeSession())
                return namedJdbc.query("SELECT username FROM employees WHERE id = :id",
                    Map.of("id", session.employeeId()), (rs, i) -> rs.getString(1))
                    .stream().findFirst();
            if (session.isDeviceSession())
                return namedJdbc.query("SELECT username FROM devices WHERE id = :id",
                    Map.of("id", session.deviceId()), (rs, i) -> rs.getString(1))
                    .stream().findFirst();
            return Optional.empty();
        }).orElse(null);
    }
}
