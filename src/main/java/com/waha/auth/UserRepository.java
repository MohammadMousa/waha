package com.waha.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
            .usingColumns("username", "password_hash");
    }

    public record PasswordRecord(long id, String username, String passwordHash) {}

    // ── public register / login ───────────────────────────────────────────────

    public boolean existsByUsername(String username) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE username = :username",
            Map.of("username", username), Integer.class
        );
        return count != null && count > 0;
    }

    public long create(String username, String passwordHash) {
        Map<String, Object> row = new HashMap<>();
        row.put("username", username);
        row.put("password_hash", passwordHash);
        return usersInsert.executeAndReturnKey(row).longValue();
    }

    // Disabled accounts cannot log in.
    public Optional<PasswordRecord> findPasswordRecord(String username) {
        List<PasswordRecord> results = namedJdbc.query(
            "SELECT id, username, password_hash FROM users WHERE username = :username AND enabled = 1",
            Map.of("username", username),
            (rs, i) -> new PasswordRecord(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"))
        );
        return results.stream().findFirst();
    }

    public Optional<User> findById(long id) {
        List<User> results = namedJdbc.query(
            "SELECT id, username, account_type, enabled, first_name, last_name, phone FROM users WHERE id = :id",
            Map.of("id", id),
            (rs, i) -> new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("account_type"),
                rs.getBoolean("enabled"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("phone")
            )
        );
        return results.stream().findFirst();
    }

    // ── admin CRUD ────────────────────────────────────────────────────────────

    public record UserAdminView(
        long id, String username, String accountType, boolean enabled,
        String firstName, String lastName, String phone,
        LocalDateTime createdAt,
        String roleName, Long storeId, String storeName,
        LocalDateTime lastLoginAt
    ) {}

    public List<UserAdminView> findAll(String accountTypeFilter) {
        String sql = """
            SELECT u.id, u.username, u.account_type, u.enabled,
                   u.first_name, u.last_name, u.phone, u.created_at,
                   (SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                    WHERE ur.user_id = u.id AND ur.store_id != 0
                    ORDER BY ur.store_id DESC LIMIT 1) AS role_name,
                   (SELECT ur.store_id FROM user_roles ur
                    WHERE ur.user_id = u.id AND ur.store_id != 0
                    ORDER BY ur.store_id DESC LIMIT 1) AS store_id,
                   (SELECT s.name FROM user_roles ur JOIN stores s ON s.id = ur.store_id
                    WHERE ur.user_id = u.id AND ur.store_id != 0
                    ORDER BY ur.store_id DESC LIMIT 1) AS store_name,
                   (SELECT MAX(created_at) FROM user_sessions WHERE user_id = u.id) AS last_login_at
            FROM users u
            WHERE (:accountType IS NULL OR u.account_type = :accountType)
            ORDER BY u.id DESC
            """;
        MapSqlParameterSource listParams = new MapSqlParameterSource("accountType", accountTypeFilter);
        return namedJdbc.query(sql, listParams,
            (rs, i) -> new UserAdminView(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("account_type"),
                rs.getBoolean("enabled"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("phone"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("role_name"),
                rs.getObject("store_id", Long.class),
                rs.getString("store_name"),
                rs.getObject("last_login_at", LocalDateTime.class)
            )
        );
    }

    public long createAccount(String username, String passwordHash, String accountType,
                              boolean enabled, String firstName, String lastName, String phone) {
        Map<String, Object> row = new HashMap<>();
        row.put("username", username);
        row.put("password_hash", passwordHash);
        row.put("account_type", accountType);
        row.put("enabled", enabled ? 1 : 0);
        row.put("first_name", firstName);
        row.put("last_name", lastName);
        row.put("phone", phone);
        SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("users")
            .usingGeneratedKeyColumns("id")
            .usingColumns("username", "password_hash", "account_type", "enabled",
                          "first_name", "last_name", "phone");
        return insert.executeAndReturnKey(row).longValue();
    }

    public void patch(long id, JsonNode body) {
        List<String> setClauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);

        if (body.has("enabled")) {
            setClauses.add("enabled = :enabled");
            params.addValue("enabled", body.get("enabled").asBoolean() ? 1 : 0);
        }
        if (body.has("firstName")) {
            setClauses.add("first_name = :firstName");
            params.addValue("firstName", body.get("firstName").asText(null));
        }
        if (body.has("lastName")) {
            setClauses.add("last_name = :lastName");
            params.addValue("lastName", body.get("lastName").asText(null));
        }
        if (body.has("phone")) {
            setClauses.add("phone = :phone");
            params.addValue("phone", body.get("phone").asText(null));
        }
        if (body.has("accountType")) {
            setClauses.add("account_type = :accountType");
            params.addValue("accountType", body.get("accountType").asText());
        }

        if (!setClauses.isEmpty()) {
            namedJdbc.update(
                "UPDATE users SET " + String.join(", ", setClauses) + " WHERE id = :id",
                params
            );
        }
    }

    public void delete(long id) {
        namedJdbc.update("DELETE FROM users WHERE id = :id", Map.of("id", id));
    }

    public void updatePassword(long id, String passwordHash) {
        namedJdbc.update("UPDATE users SET password_hash = :h WHERE id = :id",
            Map.of("h", passwordHash, "id", id));
    }

    public boolean existsById(long id) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = :id", Map.of("id", id), Integer.class);
        return count != null && count > 0;
    }
}
