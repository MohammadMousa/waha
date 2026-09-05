package com.waha.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class RoleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert userRolesInsert;

    public RoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRolesInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("user_roles")
            .usingColumns("user_id", "role_id", "scope_id");
    }

    public Role resolveRole(long userId, List<Long> scopeChain) {
        if (scopeChain.isEmpty()) return Role.ANONYMOUS;

        String inClause = scopeChain.stream().map(String::valueOf).collect(Collectors.joining(","));
        String fieldList = scopeChain.stream().map(String::valueOf).collect(Collectors.joining(","));

        List<String> results = jdbcTemplate.query(
            "SELECT r.name FROM user_roles ur " +
            "JOIN roles r ON ur.role_id = r.id " +
            "WHERE ur.user_id = ? AND ur.scope_id IN (" + inClause + ") " +
            "ORDER BY FIELD(ur.scope_id, " + fieldList + ") " +
            "LIMIT 1",
            (rs, i) -> rs.getString("name"),
            userId
        );

        if (results.isEmpty()) return Role.ANONYMOUS;
        try {
            return Role.valueOf(results.get(0));
        } catch (IllegalArgumentException e) {
            return Role.ANONYMOUS;
        }
    }

    public Set<String> resolvePermissions(long userId, List<Long> scopeChain) {
        Role role = resolveRole(userId, scopeChain);
        return Permission.BY_ROLE.getOrDefault(role, Set.of())
            .stream().map(Enum::name).collect(Collectors.toSet());
    }

    public void assignRole(long userId, Role role, long scopeId) {
        Long roleId = jdbcTemplate.queryForObject(
            "SELECT id FROM roles WHERE name = ?", Long.class, role.name());
        if (roleId == null) throw new IllegalStateException("Role not found: " + role);
        userRolesInsert.execute(Map.of("user_id", userId, "role_id", roleId, "scope_id", scopeId));
    }

    public void removeRole(long userId, Role role, long scopeId) {
        jdbcTemplate.update(
            "DELETE ur FROM user_roles ur JOIN roles r ON ur.role_id = r.id " +
            "WHERE ur.user_id = ? AND r.name = ? AND ur.scope_id = ?",
            userId, role.name(), scopeId
        );
    }
}
