package com.waha.employee;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class EmployeeRepository {

    public record EmployeeAuth(long id, long organizationId, String pinCode, boolean enabled) {}

    public record Employee(long id, long organizationId, String username) {}

    public record EmployeeAdminView(
        long id, String username,
        String firstName, String lastName,
        String gender, LocalDate birthDate,
        String email, String phone,
        LocalDate hiredAt, String address, String notes,
        Long avatarResourceId, boolean enabled,
        LocalDateTime createdAt,
        String roleName, Long storeId, String storeName,
        Instant lockedUntil
    ) {}

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SimpleJdbcInsert insert;

    public EmployeeRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.insert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("employees")
            .usingGeneratedKeyColumns("id")
            .usingColumns("organization_id", "username", "pin_code",
                          "first_name", "last_name", "gender", "birth_date",
                          "email", "phone", "hired_at", "address", "notes",
                          "avatar_resource_id", "enabled");
    }

    // ── POS auth ──────────────────────────────────────────────────────────────

    public Optional<EmployeeAuth> findAuthRecord(String username, long orgId) {
        List<EmployeeAuth> results = namedJdbc.query(
            "SELECT id, organization_id, pin_code, enabled FROM employees " +
            "WHERE username = :username AND organization_id = :orgId",
            Map.of("username", username, "orgId", orgId),
            (rs, i) -> new EmployeeAuth(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("pin_code"),
                rs.getBoolean("enabled")
            )
        );
        return results.stream().findFirst();
    }

    public Map<String, Object> findProfileForSession(long employeeId) {
        List<Map<String, Object>> rows = namedJdbc.queryForList(
            "SELECT CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')) AS employeeName, " +
            "       (SELECT r.name FROM employee_roles er JOIN roles r ON r.id = er.role_id " +
            "        WHERE er.employee_id = e.id ORDER BY er.role_id LIMIT 1) AS roleName " +
            "FROM employees e WHERE e.id = :id",
            Map.of("id", employeeId)
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Optional<Employee> findById(long id) {
        List<Employee> results = namedJdbc.query(
            "SELECT id, organization_id, username FROM employees WHERE id = :id",
            Map.of("id", id),
            (rs, i) -> new Employee(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("username"))
        );
        return results.stream().findFirst();
    }

    // ── admin CRUD ────────────────────────────────────────────────────────────

    public List<EmployeeAdminView> findAll(long orgId) {
        return namedJdbc.query("""
            SELECT e.id, e.username, e.first_name, e.last_name, e.gender, e.birth_date,
                   e.email, e.phone, e.hired_at, e.address, e.notes,
                   e.avatar_resource_id, e.enabled, e.created_at, e.locked_until,
                   (SELECT r.name FROM employee_roles er JOIN roles r ON r.id = er.role_id
                    WHERE er.employee_id = e.id ORDER BY er.scope_id DESC LIMIT 1) AS role_name,
                   (SELECT er.scope_id FROM employee_roles er
                    WHERE er.employee_id = e.id AND er.scope_type = 'BRANCH'
                    ORDER BY er.scope_id DESC LIMIT 1) AS store_id,
                   (SELECT s.name FROM employee_roles er JOIN stores s ON s.id = er.scope_id
                    WHERE er.employee_id = e.id AND er.scope_type = 'BRANCH'
                    ORDER BY er.scope_id DESC LIMIT 1) AS store_name
            FROM employees e
            WHERE e.organization_id = :orgId
            ORDER BY e.id DESC
            """,
            Map.of("orgId", orgId),
            (rs, i) -> {
                java.sql.Timestamp lockedTs = rs.getTimestamp("locked_until");
                return new EmployeeAdminView(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getString("gender"),
                    rs.getObject("birth_date", LocalDate.class),
                    rs.getString("email"),
                    rs.getString("phone"),
                    rs.getObject("hired_at", LocalDate.class),
                    rs.getString("address"),
                    rs.getString("notes"),
                    rs.getObject("avatar_resource_id", Long.class),
                    rs.getBoolean("enabled"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getString("role_name"),
                    rs.getObject("store_id", Long.class),
                    rs.getString("store_name"),
                    lockedTs != null ? lockedTs.toInstant() : null
                );
            }
        );
    }

    public List<Map<String, Object>> findStores(long employeeId) {
        return namedJdbc.queryForList(
            "SELECT s.id, s.name, s.display_name FROM employee_stores es JOIN stores s ON s.id = es.store_id " +
            "WHERE es.employee_id = :id ORDER BY s.name",
            Map.of("id", employeeId)
        );
    }

    public boolean existsByUsername(String username, long orgId) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE username = :username AND organization_id = :orgId",
            Map.of("username", username, "orgId", orgId), Integer.class
        );
        return count != null && count > 0;
    }

    public boolean existsById(long id) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE id = :id", Map.of("id", id), Integer.class
        );
        return count != null && count > 0;
    }

    public long create(long orgId, String username, String pinCode,
                       String firstName, String lastName, String gender,
                       LocalDate birthDate, String email, String phone,
                       LocalDate hiredAt, String address, String notes,
                       Long avatarResourceId, boolean enabled) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("organization_id",    orgId)
            .addValue("username",           username)
            .addValue("pin_code",           pinCode)
            .addValue("first_name",         firstName)
            .addValue("last_name",          lastName)
            .addValue("gender",             gender)
            .addValue("birth_date",         birthDate)
            .addValue("email",              email)
            .addValue("phone",              phone)
            .addValue("hired_at",           hiredAt)
            .addValue("address",            address)
            .addValue("notes",              notes)
            .addValue("avatar_resource_id", avatarResourceId)
            .addValue("enabled",            enabled ? 1 : 0);
        return insert.executeAndReturnKey(p).longValue();
    }

    public void patch(long id, JsonNode body) {
        List<String> set = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        if (body.has("pinCode"))    { set.add("pin_code = :pinCode");           p.addValue("pinCode",   body.get("pinCode").asText()); }
        if (body.has("firstName"))  { set.add("first_name = :fn");              p.addValue("fn",        body.get("firstName").asText(null)); }
        if (body.has("lastName"))   { set.add("last_name = :ln");               p.addValue("ln",        body.get("lastName").asText(null)); }
        if (body.has("gender"))     { set.add("gender = :gender");              p.addValue("gender",    body.get("gender").asText(null)); }
        if (body.has("birthDate"))  { set.add("birth_date = :bd");              p.addValue("bd",        body.get("birthDate").asText(null)); }
        if (body.has("email"))      { set.add("email = :email");                p.addValue("email",     body.get("email").asText(null)); }
        if (body.has("phone"))      { set.add("phone = :phone");                p.addValue("phone",     body.get("phone").asText(null)); }
        if (body.has("hiredAt"))    { set.add("hired_at = :ha");                p.addValue("ha",        body.get("hiredAt").asText(null)); }
        if (body.has("address"))    { set.add("address = :address");            p.addValue("address",   body.get("address").asText(null)); }
        if (body.has("notes"))      { set.add("notes = :notes");                p.addValue("notes",     body.get("notes").asText(null)); }
        if (body.has("enabled"))    { set.add("enabled = :enabled");            p.addValue("enabled",   body.get("enabled").asBoolean() ? 1 : 0); }
        if (body.has("avatarResourceId")) {
            set.add("avatar_resource_id = :ari");
            JsonNode v = body.get("avatarResourceId");
            p.addValue("ari", v.isNull() ? null : v.longValue());
        }
        if (set.isEmpty()) return;
        namedJdbc.update("UPDATE employees SET " + String.join(", ", set) + " WHERE id = :id", p);
    }

    public void delete(long id) {
        namedJdbc.update("DELETE FROM employees WHERE id = :id", Map.of("id", id));
    }

    public void addStore(long employeeId, long storeId) {
        namedJdbc.update(
            "INSERT IGNORE INTO employee_stores (employee_id, store_id) VALUES (:eid, :sid)",
            Map.of("eid", employeeId, "sid", storeId)
        );
    }

    public void removeStore(long employeeId, long storeId) {
        namedJdbc.update(
            "DELETE FROM employee_stores WHERE employee_id = :eid AND store_id = :sid",
            Map.of("eid", employeeId, "sid", storeId)
        );
    }

    // ── permissions ──────────────────────────────────────────────────────────

    // Union of permissions from employee_roles covering the given store.
    public java.util.Set<String> resolvePermissions(long employeeId, long storeId) {
        List<String> roleNames = namedJdbc.query(
            "SELECT r.name FROM employee_roles er JOIN roles r ON r.id = er.role_id " +
            "WHERE er.employee_id = :eid " +
            "AND ((er.scope_type = 'BRANCH' AND er.scope_id = :sid) " +
            "  OR (er.scope_type = 'BRANCH_GROUP' AND er.scope_id = " +
            "      (SELECT branch_group_id FROM stores WHERE id = :sid)))",
            Map.of("eid", employeeId, "sid", storeId),
            (rs, i) -> rs.getString("name")
        );
        return roleNames.stream()
            .flatMap(name -> {
                try {
                    return com.waha.auth.Permission.BY_ROLE
                        .getOrDefault(com.waha.auth.Role.valueOf(name), java.util.Set.of())
                        .stream();
                } catch (IllegalArgumentException e) {
                    return java.util.stream.Stream.empty();
                }
            })
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toSet());
    }

    public Optional<Long> findPrimaryStoreId(long employeeId) {
        List<Long> results = namedJdbc.query(
            "SELECT store_id FROM employee_stores WHERE employee_id = :id LIMIT 1",
            Map.of("id", employeeId), (rs, i) -> rs.getLong(1)
        );
        return results.stream().findFirst();
    }

    // ── role assignment ───────────────────────────────────────────────────────

    public void assignRole(long employeeId, long roleId, String scopeType, long scopeId) {
        namedJdbc.update(
            "INSERT IGNORE INTO employee_roles (employee_id, role_id, scope_type, scope_id) VALUES (:eid, :rid, :st, :sid)",
            Map.of("eid", employeeId, "rid", roleId, "st", scopeType, "sid", scopeId)
        );
    }

    public void clearRoles(long employeeId) {
        namedJdbc.update("DELETE FROM employee_roles WHERE employee_id = :id", Map.of("id", employeeId));
    }

    public Long roleIdByName(String roleName) {
        List<Long> results = namedJdbc.query(
            "SELECT id FROM roles WHERE name = :name", Map.of("name", roleName), (rs, i) -> rs.getLong(1)
        );
        return results.stream().findFirst().orElse(null);
    }
}
