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
import java.util.ArrayList;
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

    public UserSession requireSession(String authHeader) {
        String token = authHeader == null ? null : authHeader.replaceFirst("(?i)^Bearer ", "").trim();
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Missing or invalid session");
        }

        List<UserSession> results = namedJdbc.query(
            "SELECT token, user_id, store_id, mode FROM user_sessions WHERE token = :token AND expires_at > NOW()",
            Map.of("token", token),
            (rs, i) -> {
                long storeId = rs.getLong("store_id");
                Long storeIdBoxed = rs.wasNull() ? null : storeId;
                return new UserSession(rs.getString("token"), rs.getLong("user_id"), storeIdBoxed, rs.getString("mode"));
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

    // Permission chain for a store: [storeId, companyAnchor(1), legacy(0)].
    // storeId=null means no store context — chain is just [0].
    public List<Long> buildChain(Long storeId) {
        if (storeId == null) return List.of(0L);
        List<Long> chain = new ArrayList<>();
        chain.add(storeId);
        if (storeId != 1L) chain.add(1L);
        chain.add(0L);
        return chain;
    }

    public Set<String> resolvePermissions(long userId, Long storeId) {
        return roleRepository.resolvePermissions(userId, buildChain(storeId));
    }

    public void requirePermission(String authHeader, Permission permission, Long storeId) {
        UserSession session = requireSession(authHeader);
        Set<String> perms = resolvePermissions(session.userId(), storeId);
        if (!perms.contains(permission.name())) {
            throw new ForbiddenException("Permission denied: " + permission.name());
        }
    }

    public String resolveUsername(String explicit, String authHeader) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return tryResolveSession(authHeader)
            .flatMap(session -> userRepository.findById(session.userId()))
            .map(User::username)
            .orElse(null);
    }
}
