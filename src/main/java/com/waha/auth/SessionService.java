package com.waha.auth;

import com.waha.common.ForbiddenException;
import com.waha.common.UnauthorizedException;
import com.waha.store.StoreRepository;
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
    private final StoreRepository storeRepository;
    private final SecureRandom random = new SecureRandom();

    @Value("${waha.session.ttl-days:30}")
    private int ttlDays;

    public SessionService(NamedParameterJdbcTemplate namedJdbc, UserRepository userRepository,
                          RoleRepository roleRepository, StoreRepository storeRepository) {
        this.namedJdbc = namedJdbc;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.storeRepository = storeRepository;
    }

    // 32 random bytes -> 64 hex chars, 256 bits of entropy. Not a JWT -
    // just an opaque bearer token looked up server-side on every call. Same
    // "unguessable is enough, no crypto library needed" principle already
    // used for order ids.
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

    // Strips a "Bearer " prefix if present, looks up the token, and rejects
    // missing/unknown/expired sessions uniformly - see UnauthorizedException.
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

    // Null mode = clear (revert to unset). Validated upstream by AuthController.
    public void setMode(String token, String mode) {
        namedJdbc.update(
            "UPDATE user_sessions SET mode = :mode WHERE token = :token",
            Map.of("mode", mode, "token", token)
        );
    }

    public void deleteSession(String token) {
        namedJdbc.update("DELETE FROM user_sessions WHERE token = :token", Map.of("token", token));
    }

    // Non-throwing variant of requireSession - a missing/invalid session
    // here just means "guest", not an error, so callers that want to
    // *optionally* use session context (resolveStoreId/resolveUsername
    // below) don't have to catch UnauthorizedException themselves.
    public Optional<UserSession> tryResolveSession(String authHeader) {
        try {
            return Optional.of(requireSession(authHeader));
        } catch (UnauthorizedException e) {
            return Optional.empty();
        }
    }

    // Precedence: an explicit value always wins - so a call that already
    // worked before session support existed keeps working identically.
    // Otherwise, fall back to the "current store" set by POST
    // /api/auth/store, if a valid session is present. Returns null if
    // neither is available; callers turn that into their own "storeId is
    // required" error. One place resolves this so browse, quote, and
    // checkout can't silently disagree about where a storeId comes from.
    public Long resolveStoreId(Long explicit, String authHeader) {
        if (explicit != null) return explicit;
        return tryResolveSession(authHeader).map(UserSession::storeId).orElse(null);
    }

    // Builds the store scope chain (most-specific first, always ending with 0
    // for system-wide coverage). storeId=null → chain is just [0].
    public List<Long> buildChain(Long storeId) {
        List<Long> chain = storeId != null
            ? new ArrayList<>(storeRepository.resolveScopeChain(storeId))
            : new ArrayList<>();
        if (chain.isEmpty() || chain.get(chain.size() - 1) != 0L) chain.add(0L);
        return chain;
    }

    // Resolved permissions for a user at a given store (or system-wide if storeId=null).
    public Set<String> resolvePermissions(long userId, Long storeId) {
        return roleRepository.resolvePermissions(userId, buildChain(storeId));
    }

    // Throws 403 if the session user lacks the required permission at storeId.
    public void requirePermission(String authHeader, Permission permission, Long storeId) {
        UserSession session = requireSession(authHeader);
        Set<String> perms = resolvePermissions(session.userId(), storeId);
        if (!perms.contains(permission.name())) {
            throw new ForbiddenException("Permission denied: " + permission.name());
        }
    }

    // Same precedence as resolveStoreId. A logged-in session's real
    // username is used automatically once authenticated - callers don't
    // need to keep passing it. A guest (no session) still has to supply
    // something themselves (phone number, generated id, etc.) - this
    // deliberately does not invent a fallback for that case.
    public String resolveUsername(String explicit, String authHeader) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return tryResolveSession(authHeader)
            .flatMap(session -> userRepository.findById(session.userId()))
            .map(User::username)
            .orElse(null);
    }
}
