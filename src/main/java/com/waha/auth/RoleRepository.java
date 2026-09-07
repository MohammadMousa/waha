package com.waha.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Repository
public class RoleRepository {

    // Matches every user_roles row whose scope covers the given store via the hierarchy:
    //   SYSTEM       → always (platform-level, e.g. SUPER_ADMIN)
    //   COMPANY      → scope_id = stores.organization_id
    //   BRANCH_GROUP → scope_id = stores.branch_group_id
    //   BRANCH       → scope_id = stores.id  (exact store)
    private static final String ROLES_BY_STORE_SQL =
        "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id " +
        "WHERE ur.user_id = ? " +
        "AND (ur.scope_type = 'SYSTEM' " +
        "  OR (ur.scope_type = 'BRANCH'       AND ur.scope_id = ?) " +
        "  OR (ur.scope_type = 'BRANCH_GROUP' AND ur.scope_id = (SELECT branch_group_id FROM stores WHERE id = ?)) " +
        "  OR (ur.scope_type = 'COMPANY'      AND ur.scope_id = (SELECT organization_id  FROM stores WHERE id = ?)))";

    // Matches user_roles rows for an org-level check (no specific store needed):
    //   SYSTEM  → always
    //   COMPANY → scope_id = orgId
    private static final String ROLES_BY_ORG_SQL =
        "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id " +
        "WHERE ur.user_id = ? " +
        "AND (ur.scope_type = 'SYSTEM' " +
        "  OR (ur.scope_type = 'COMPANY' AND ur.scope_id = ?))";

    private final JdbcTemplate jdbcTemplate;

    public RoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Returns the names of all roles that cover the given store for this user.
    // Used to detect mode (e.g. KIOSK auto-detect on login).
    public List<String> resolveRoleNames(long userId, Long storeId) {
        if (storeId == null) return jdbcTemplate.query(
            "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id " +
            "WHERE ur.user_id = ? AND ur.scope_type = 'SYSTEM'",
            (rs, i) -> rs.getString("name"), userId);
        return jdbcTemplate.query(ROLES_BY_STORE_SQL,
            (rs, i) -> rs.getString("name"),
            userId, storeId, storeId, storeId);
    }

    // Returns the union of permissions from all roles that cover the given store.
    // storeId=null → only SYSTEM-scope roles match (platform-only operations).
    public Set<String> resolvePermissions(long userId, Long storeId) {
        List<String> roleNames = storeId == null
            ? jdbcTemplate.query(
                "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id " +
                "WHERE ur.user_id = ? AND ur.scope_type = 'SYSTEM'",
                (rs, i) -> rs.getString("name"), userId)
            : jdbcTemplate.query(ROLES_BY_STORE_SQL,
                (rs, i) -> rs.getString("name"),
                userId, storeId, storeId, storeId);
        return permissionsFromRoleNames(roleNames);
    }

    // Returns the union of permissions from all roles that cover the given org.
    // Used for org-level operations where no specific store is in scope.
    public Set<String> resolvePermissionsForOrg(long userId, long orgId) {
        List<String> roleNames = jdbcTemplate.query(ROLES_BY_ORG_SQL,
            (rs, i) -> rs.getString("name"), userId, orgId);
        return permissionsFromRoleNames(roleNames);
    }

    private Set<String> permissionsFromRoleNames(List<String> roleNames) {
        return roleNames.stream()
            .flatMap(name -> {
                try {
                    return Permission.BY_ROLE
                        .getOrDefault(Role.valueOf(name), Set.of())
                        .stream();
                } catch (IllegalArgumentException e) {
                    return Stream.empty();
                }
            })
            .map(Enum::name)
            .collect(Collectors.toSet());
    }

    // Returns the store assigned to this user via a BRANCH-scope role row, if any.
    // Used at login to prefer the user's actual store over the org-level default.
    public Optional<Long> findAssignedBranchStoreId(long userId) {
        List<Long> results = jdbcTemplate.query(
            "SELECT ur.scope_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE ur.user_id = ? AND ur.scope_type = 'BRANCH' LIMIT 1",
            (rs, i) -> rs.getLong(1), userId
        );
        return results.stream().findFirst();
    }

    public void assignRole(long userId, Role role, long scopeId) {
        Long roleId = jdbcTemplate.queryForObject(
            "SELECT id FROM roles WHERE name = ?", Long.class, role.name());
        if (roleId == null) throw new IllegalStateException("Role not found: " + role);
        String scopeType = role == Role.SUPER_ADMIN ? "SYSTEM"
            : role == Role.ORGANIZATION_OWNER ? "COMPANY"
            : "BRANCH";
        long actualScopeId = (role == Role.SUPER_ADMIN) ? 0L : scopeId;
        jdbcTemplate.update(
            "INSERT IGNORE INTO user_roles (user_id, role_id, scope_type, scope_id) VALUES (?,?,?,?)",
            userId, roleId, scopeType, actualScopeId
        );
    }

    public void removeRole(long userId, Role role, long scopeId) {
        long actualScopeId = (role == Role.SUPER_ADMIN) ? 0L : scopeId;
        jdbcTemplate.update(
            "DELETE ur FROM user_roles ur JOIN roles r ON ur.role_id = r.id " +
            "WHERE ur.user_id = ? AND r.name = ? AND ur.scope_id = ?",
            userId, role.name(), actualScopeId
        );
    }
}
