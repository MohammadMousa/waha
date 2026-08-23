package com.waha.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final SimpleJdbcInsert usersInsert;

    public UserRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
        this.usersInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("users")
            .usingGeneratedKeyColumns("id")
            .usingColumns("username", "password_hash");
    }

    public record PasswordRecord(long id, String username, String passwordHash) {}

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
        Number key = usersInsert.executeAndReturnKey(row);
        return key.longValue();
    }

    // Only called by login, where the hash actually needs comparing -
    // everywhere else uses findById/User, which never carries the hash at
    // all.
    public Optional<PasswordRecord> findPasswordRecord(String username) {
        List<PasswordRecord> results = namedJdbc.query(
            "SELECT id, username, password_hash FROM users WHERE username = :username",
            Map.of("username", username),
            (rs, i) -> new PasswordRecord(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"))
        );
        return results.stream().findFirst();
    }

    public Optional<User> findById(long id) {
        List<User> results = namedJdbc.query(
            "SELECT id, username FROM users WHERE id = :id",
            Map.of("id", id),
            (rs, i) -> new User(rs.getLong("id"), rs.getString("username"))
        );
        return results.stream().findFirst();
    }
}
