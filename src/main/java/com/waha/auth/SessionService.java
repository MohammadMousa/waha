package com.waha.auth;

import com.waha.common.ForbiddenException;
import com.waha.common.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class SessionService {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SecureRandom random = new SecureRandom();

    @Value("${waha.session.ttl-days:30}")
    private int ttlDays;

    public SessionService(NamedParameterJdbcTemplate namedJdbc, UserRepository userRepository,
                          RoleRepository roleRepository) {
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
        Instant expiresAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS);
        Map<String, Object> params = new HashMap<>();
        params.put("token", token);
        params.put("userId", userId);
        params.put("expiresAt", Timestamp.from(expiresAt));
        namedJdbc.update(
            "INSERT INTO user_sessions (token, user_id, expires_at) VALUES (:token, :userId, :expiresAt)",
            params
        );
        return token;
    }

    public String createEmployeeSession(long employeeId) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS);
        namedJdbc.update(
            "INSERT INTO user_sessions (token, employee_id, expires_at) VALUES (:token, :eid, :exp)",
            Map.of("token", token, "eid", employeeId, "exp", Timestamp.from(expiresAt))
        );
        return token;
    }

    public String createDeviceSession(long deviceId) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS);
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
            "WHERE us.token = :token AND us.expires_at > NOW()",
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

        return results.stream().findFirst()
            .orElseThrow(() -> new UnauthorizedException("Missing or invalid session"));
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
        namedJdbc.update("DELETE FROM user_sessions WHERE token = :token", Map.of("token", token));
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
