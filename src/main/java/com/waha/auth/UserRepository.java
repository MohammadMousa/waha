package com.waha.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SimpleJdbcInsert usersInsert;

    public UserRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.usersInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("users")
            .usingGeneratedKeyColumns("id")
            .usingColumns("username", "password_hash", "organization_id");
    }

    public record PasswordRecord(long id, String username, String passwordHash) {}

    // ── public register / login ───────────────────────────────────────────────

    public boolean existsByUsername(String username, long organizationId) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE username = :username AND organization_id = :orgId",
            Map.of("username", username, "orgId", organizationId), Integer.class
        );
        return count != null && count > 0;
    }

    public long create(String username, String passwordHash, long organizationId) {
        Map<String, Object> row = new HashMap<>();
        row.put("username", username);
        row.put("password_hash", passwordHash);
        row.put("organization_id", organizationId);
        return usersInsert.executeAndReturnKey(row).longValue();
    }

    // Admin-app users only (users table). No enabled flag — admin accounts are
    // disabled by deleting their role or the account, not an enabled column.
    public Optional<PasswordRecord> findPasswordRecord(String username, Long organizationId) {
        String sql = organizationId != null
            ? "SELECT id, username, password_hash FROM users WHERE username = :username AND organization_id = :orgId"
            : "SELECT id, username, password_hash FROM users WHERE username = :username";
        MapSqlParameterSource params = new MapSqlParameterSource("username", username);
        if (organizationId != null) params.addValue("orgId", organizationId);
        List<PasswordRecord> results = namedJdbc.query(sql, params,
            (rs, i) -> new PasswordRecord(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"))
        );
        return results.stream().findFirst();
    }

    public Optional<User> findById(long id) {
        List<User> results = namedJdbc.query(
            "SELECT id, organization_id, username FROM users WHERE id = :id",
            Map.of("id", id),
            (rs, i) -> new User(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("username"))
        );
        return results.stream().findFirst();
    }

    // ── admin CRUD ────────────────────────────────────────────────────────────

    // Admin accounts carry no profile data — profile lives in employees (POS).
    public record UserAdminView(
        long id, String username,
        LocalDateTime createdAt,
        String roleName, Long storeId, String storeName,
        LocalDateTime lastLoginAt
    ) {}

    public List<UserAdminView> findAll(String roleFilter, long orgId) {
        String sql = """
            SELECT u.id, u.username, u.created_at,
                   (SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                    WHERE ur.user_id = u.id
                    ORDER BY CASE ur.scope_type WHEN 'SYSTEM' THEN 0 ELSE 1 END, ur.scope_id DESC LIMIT 1) AS role_name,
                   (SELECT NULLIF(ur.scope_id, 0) FROM user_roles ur
                    WHERE ur.user_id = u.id AND ur.scope_type != 'SYSTEM'
                    ORDER BY ur.scope_id DESC LIMIT 1) AS store_id,
                   (SELECT s.name FROM user_roles ur JOIN stores s ON s.id = ur.scope_id
                    WHERE ur.user_id = u.id AND ur.scope_type = 'BRANCH'
                    ORDER BY ur.scope_id DESC LIMIT 1) AS store_name,
                   (SELECT MAX(created_at) FROM user_sessions WHERE user_id = u.id) AS last_login_at
            FROM users u
            WHERE (:role IS NULL OR EXISTS (
                      SELECT 1 FROM user_roles ur2 JOIN roles r2 ON r2.id = ur2.role_id
                      WHERE ur2.user_id = u.id AND r2.name = :role))
              AND (:orgId = 0 OR u.organization_id = :orgId)
            ORDER BY u.id DESC
            """;
        MapSqlParameterSource listParams = new MapSqlParameterSource("role", roleFilter)
            .addValue("orgId", orgId);
        return namedJdbc.query(sql, listParams,
            (rs, i) -> new UserAdminView(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("role_name"),
                rs.getObject("store_id", Long.class),
                rs.getString("store_name"),
                rs.getObject("last_login_at", LocalDateTime.class)
            )
        );
    }

    public void delete(long id) {
        namedJdbc.update("DELETE FROM users WHERE id = :id", Map.of("id", id));
    }

    public void updatePassword(long id, String passwordHash) {
        namedJdbc.update("UPDATE users SET password_hash = :h WHERE id = :id",
            Map.of("h", passwordHash, "id", id));
    }

    public Optional<String> findPasswordHashById(long id) {
        List<String> results = namedJdbc.queryForList(
            "SELECT password_hash FROM users WHERE id = :id", Map.of("id", id), String.class);
        return results.stream().findFirst();
    }

    public boolean existsById(long id) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = :id", Map.of("id", id), Integer.class);
        return count != null && count > 0;
    }
}
