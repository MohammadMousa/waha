package com.waha.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.common.ErrorResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

// Runtime-configurable roles. The list of permissions stays in code; what a role contains is data.
//   built-in roles  : organization_id is NULL, shared by every organization, editable by SUPER_ADMIN only, never deleted
//   custom roles    : belong to one organization, managed by MANAGE_ROLES inside that organization
// Giving roles to people needs ASSIGN_ROLES. Nobody can hand out a permission they do not hold.
@RestController
@RequestMapping("/api/admin")
public class RoleAdminController {

    private static final Map<Permission, String> DESCRIPTIONS = new LinkedHashMap<>();
    static {
        DESCRIPTIONS.put(Permission.VIEW_PRODUCTS, "See products");
        DESCRIPTIONS.put(Permission.EDIT_PRODUCTS, "Create, change and delete products");
        DESCRIPTIONS.put(Permission.MANAGE_CATEGORIES, "Create, change and delete categories");
        DESCRIPTIONS.put(Permission.VIEW_OWN_ORDERS, "See the person's own orders");
        DESCRIPTIONS.put(Permission.VIEW_ORDER_HISTORY, "See order history");
        DESCRIPTIONS.put(Permission.VIEW_ALL_ORDERS, "See every order in the store");
        DESCRIPTIONS.put(Permission.PROCESS_ORDERS, "Create orders and take payment");
        DESCRIPTIONS.put(Permission.MANAGE_USERS, "Manage admin accounts (super admin only)");
        DESCRIPTIONS.put(Permission.MANAGE_EMPLOYEES, "Manage POS employees");
        DESCRIPTIONS.put(Permission.MANAGE_DEVICES, "Manage kiosk devices");
        DESCRIPTIONS.put(Permission.MANAGE_STORES, "Manage stores and branch groups");
        DESCRIPTIONS.put(Permission.MANAGE_SYSTEM, "Platform settings (super admin only)");
        DESCRIPTIONS.put(Permission.EDIT_RESOURCES, "Upload and change pictures and files");
        DESCRIPTIONS.put(Permission.VIEW_INVENTORY, "See stock levels and history");
        DESCRIPTIONS.put(Permission.PROCESS_INVENTORY, "Count stock, transfer, return, visits");
        DESCRIPTIONS.put(Permission.MANAGE_INVENTORY, "Inventory settings, approvals and full reports");
        DESCRIPTIONS.put(Permission.VIEW_OFFERS, "See offers");
        DESCRIPTIONS.put(Permission.MANAGE_OFFERS, "Create, change and delete offers");
        DESCRIPTIONS.put(Permission.VIEW_COUPONS, "See coupons and redemptions");
        DESCRIPTIONS.put(Permission.MANAGE_COUPONS, "Create, change and delete coupons");
        DESCRIPTIONS.put(Permission.UPLOAD_LOGS, "Upload device diagnostic logs");
        DESCRIPTIONS.put(Permission.MANAGE_ROLES, "Create, change and delete the organization's own roles");
        DESCRIPTIONS.put(Permission.ASSIGN_ROLES, "Give or take away roles for users and employees");
    }

    private final SessionService sessionService;
    private final RoleCatalog roleCatalog;
    private final JdbcTemplate jdbc;

    public RoleAdminController(SessionService sessionService, RoleCatalog roleCatalog, JdbcTemplate jdbc) {
        this.sessionService = sessionService;
        this.roleCatalog = roleCatalog;
        this.jdbc = jdbc;
    }

    // ── permission list ───────────────────────────────────────────────────────

    @GetMapping("/permissions")
    public ResponseEntity<?> permissions(@RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        requireAny(session, Permission.MANAGE_ROLES, Permission.ASSIGN_ROLES);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Permission p : Permission.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.name());
            m.put("description", DESCRIPTIONS.getOrDefault(p, ""));
            m.put("assignableToRoles", !Permission.SUPER_ADMIN_ONLY.contains(p));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    // ── roles ─────────────────────────────────────────────────────────────────

    @GetMapping("/roles")
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        requireAny(session, Permission.MANAGE_ROLES, Permission.ASSIGN_ROLES);
        long org = session.organizationId();
        Map<Long, Integer> used = assignmentCounts();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoleCatalog.RoleInfo r : roleCatalog.all()) {
            if (!r.builtIn() && org > 0 && r.organizationId() != org) continue;
            out.add(toMap(r, used.getOrDefault(r.id(), 0)));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/roles")
    @Transactional
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody JsonNode body) {
        UserSession session = sessionService.requireSession(auth);
        long org = session.organizationId();
        if (org <= 0) return ResponseEntity.badRequest().body(new ErrorResponse("Choose an organization first"));
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_ROLES, org);

        String name = RolePolicy.cleanName(text(body, "name"));
        Set<Permission> perms = RolePolicy.parsePermissions(strings(body, "permissions"));
        Permission missing = RolePolicy.firstNotHeld(sessionService.resolvePermissionsForOrg(session.userId(), org), perms);
        if (missing != null) return forbidden("You cannot give a permission you do not hold: " + missing.name());

        try {
            jdbc.update("INSERT INTO roles (name, organization_id, description) VALUES (?,?,?)",
                name, org, clip(text(body, "description")));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(409).body(new ErrorResponse("A role with this name already exists"));
        }
        long id = jdbc.queryForObject("SELECT id FROM roles WHERE organization_id = ? AND name = ?", Long.class, org, name);
        writePermissions(id, perms);
        roleCatalog.invalidate();
        return ResponseEntity.status(201).body(toMap(roleCatalog.find(id).orElseThrow(), 0));
    }

    @PutMapping("/roles/{id}")
    @Transactional
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id, @RequestBody JsonNode body) {
        UserSession session = sessionService.requireSession(auth);
        Optional<RoleCatalog.RoleInfo> found = roleCatalog.find(id);
        if (found.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Role not found"));
        RoleCatalog.RoleInfo role = found.get();
        long org = session.organizationId();

        Set<String> callerPerms;
        if (role.fixed()) {
            return forbidden("This role is fixed and cannot be changed");
        } else if (role.builtIn()) {
            // Built-in roles are shared by every organization, so only the platform owner edits them.
            if (!isSuperAdmin(session)) return forbidden("Only a super admin can change a built-in role");
            callerPerms = sessionService.resolvePermissions(session.userId(), null);
        } else {
            if (org > 0 && role.organizationId() != org) return ResponseEntity.status(404).body(new ErrorResponse("Role not found"));
            sessionService.requirePermissionForOrg(session, Permission.MANAGE_ROLES, role.organizationId());
            callerPerms = sessionService.resolvePermissionsForOrg(session.userId(), role.organizationId());
        }

        String name = role.name();
        if (!role.builtIn() && body.has("name")) name = RolePolicy.cleanName(text(body, "name"));
        Set<Permission> perms = body.has("permissions")
            ? RolePolicy.parsePermissions(strings(body, "permissions"))
            : EnumSet.copyOf(role.permissions().isEmpty() ? EnumSet.noneOf(Permission.class) : role.permissions());
        // Only permissions that are being added need to be held by the caller.
        Set<Permission> added = EnumSet.noneOf(Permission.class);
        added.addAll(perms);
        added.removeAll(role.permissions());
        Permission missing = RolePolicy.firstNotHeld(callerPerms, added);
        if (missing != null) return forbidden("You cannot give a permission you do not hold: " + missing.name());

        try {
            jdbc.update("UPDATE roles SET name = ?, description = ? WHERE id = ?",
                name, body.has("description") ? clip(text(body, "description")) : role.description(), id);
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(409).body(new ErrorResponse("A role with this name already exists"));
        }
        if (body.has("permissions")) writePermissions(id, perms);
        roleCatalog.invalidate();
        return ResponseEntity.ok(toMap(roleCatalog.find(id).orElseThrow(), assignmentCounts().getOrDefault(id, 0)));
    }

    @DeleteMapping("/roles/{id}")
    @Transactional
    public ResponseEntity<?> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id) {
        UserSession session = sessionService.requireSession(auth);
        Optional<RoleCatalog.RoleInfo> found = roleCatalog.find(id);
        if (found.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Role not found"));
        RoleCatalog.RoleInfo role = found.get();
        if (role.builtIn()) return forbidden("Built-in roles cannot be deleted");
        long org = session.organizationId();
        if (org > 0 && role.organizationId() != org) return ResponseEntity.status(404).body(new ErrorResponse("Role not found"));
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_ROLES, role.organizationId());

        int used = assignmentCounts().getOrDefault(id, 0);
        if (used > 0) return ResponseEntity.status(409).body(new ErrorResponse(
            "This role is given to " + used + " assignment(s). Take it away from them first"));
        jdbc.update("DELETE FROM roles WHERE id = ?", id);   // role_permissions rows go with it
        roleCatalog.invalidate();
        return ResponseEntity.ok().build();
    }

    // ── who has which role ────────────────────────────────────────────────────

    @GetMapping("/users/{id}/roles")
    public ResponseEntity<?> userRoles(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @PathVariable long id) {
        return listAssignments(auth, id, true);
    }

    @PostMapping("/users/{id}/roles")
    public ResponseEntity<?> addUserRole(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable long id, @RequestBody JsonNode body) {
        return addAssignment(auth, id, body, true);
    }

    @DeleteMapping("/users/{id}/roles/{roleId}")
    public ResponseEntity<?> removeUserRole(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable long id, @PathVariable long roleId,
                                            @RequestParam String scopeType, @RequestParam(defaultValue = "0") long scopeId) {
        return removeAssignment(auth, id, roleId, scopeType, scopeId, true);
    }

    @GetMapping("/employees/{id}/roles")
    public ResponseEntity<?> employeeRoles(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @PathVariable long id) {
        return listAssignments(auth, id, false);
    }

    @PostMapping("/employees/{id}/roles")
    public ResponseEntity<?> addEmployeeRole(@RequestHeader(value = "Authorization", required = false) String auth,
                                             @PathVariable long id, @RequestBody JsonNode body) {
        return addAssignment(auth, id, body, false);
    }

    @DeleteMapping("/employees/{id}/roles/{roleId}")
    public ResponseEntity<?> removeEmployeeRole(@RequestHeader(value = "Authorization", required = false) String auth,
                                                @PathVariable long id, @PathVariable long roleId,
                                                @RequestParam String scopeType, @RequestParam(defaultValue = "0") long scopeId) {
        return removeAssignment(auth, id, roleId, scopeType, scopeId, false);
    }

    private ResponseEntity<?> listAssignments(String auth, long subjectId, boolean user) {
        UserSession session = sessionService.requireSession(auth);
        Long targetOrg = targetOrganization(subjectId, user);
        if (targetOrg == null || !sameOrg(session, targetOrg)) return notFound();
        sessionService.requirePermissionForOrg(session, Permission.ASSIGN_ROLES, targetOrg);
        String sql = user
            ? "SELECT role_id, scope_type, scope_id FROM user_roles WHERE user_id = ?"
            : "SELECT role_id, scope_type, scope_id FROM employee_roles WHERE employee_id = ?";
        List<Map<String, Object>> out = jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            long roleId = rs.getLong("role_id");
            m.put("roleId", roleId);
            m.put("roleName", roleCatalog.find(roleId).map(RoleCatalog.RoleInfo::name).orElse(null));
            m.put("scopeType", rs.getString("scope_type"));
            m.put("scopeId", rs.getLong("scope_id"));
            return m;
        }, subjectId);
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<?> addAssignment(String auth, long subjectId, JsonNode body, boolean user) {
        UserSession session = sessionService.requireSession(auth);
        Long targetOrg = targetOrganization(subjectId, user);
        if (targetOrg == null || !sameOrg(session, targetOrg)) return notFound();
        sessionService.requirePermissionForOrg(session, Permission.ASSIGN_ROLES, targetOrg);

        Optional<RoleCatalog.RoleInfo> found = roleCatalog.find(body.path("roleId").asLong(-1));
        if (found.isEmpty()) return ResponseEntity.badRequest().body(new ErrorResponse("roleId is required and must exist"));
        RoleCatalog.RoleInfo role = found.get();
        if (!RolePolicy.assignableByApi(role))
            return forbidden("This role cannot be given here");
        if (!role.builtIn() && role.organizationId() != targetOrg)
            return ResponseEntity.badRequest().body(new ErrorResponse("This role belongs to another organization"));
        Permission missing = RolePolicy.firstNotHeld(
            sessionService.resolvePermissionsForOrg(session.userId(), targetOrg), role.permissions());
        if (missing != null) return forbidden("You cannot give a role that includes a permission you do not hold: " + missing.name());

        String scopeType = text(body, "scopeType") == null ? "" : text(body, "scopeType").toUpperCase();
        long scopeId = body.path("scopeId").asLong(0);
        ResponseEntity<?> bad = validateScope(scopeType, scopeId, targetOrg, user);
        if (bad != null) return bad;
        if (scopeType.equals("COMPANY")) scopeId = targetOrg;

        jdbc.update(user
            ? "INSERT IGNORE INTO user_roles (user_id, role_id, scope_type, scope_id) VALUES (?,?,?,?)"
            : "INSERT IGNORE INTO employee_roles (employee_id, role_id, scope_type, scope_id) VALUES (?,?,?,?)",
            subjectId, role.id(), scopeType, scopeId);
        return listAssignments(auth, subjectId, user);
    }

    private ResponseEntity<?> removeAssignment(String auth, long subjectId, long roleId, String scopeType,
                                               long scopeId, boolean user) {
        UserSession session = sessionService.requireSession(auth);
        Long targetOrg = targetOrganization(subjectId, user);
        if (targetOrg == null || !sameOrg(session, targetOrg)) return notFound();
        sessionService.requirePermissionForOrg(session, Permission.ASSIGN_ROLES, targetOrg);
        Optional<RoleCatalog.RoleInfo> found = roleCatalog.find(roleId);
        if (found.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Role not found"));
        if (!RolePolicy.assignableByApi(found.get())) return forbidden("This role cannot be taken away here");
        jdbc.update(user
            ? "DELETE FROM user_roles WHERE user_id = ? AND role_id = ? AND scope_type = ? AND scope_id = ?"
            : "DELETE FROM employee_roles WHERE employee_id = ? AND role_id = ? AND scope_type = ? AND scope_id = ?",
            subjectId, roleId, scopeType.toUpperCase(), scopeId);
        return listAssignments(auth, subjectId, user);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<?> validateScope(String scopeType, long scopeId, long org, boolean user) {
        switch (scopeType) {
            case "COMPANY":
                if (user) return null;
                break;
            case "BRANCH":
                if (count("SELECT COUNT(*) FROM stores WHERE id = ? AND organization_id = ?", scopeId, org) > 0) return null;
                return ResponseEntity.badRequest().body(new ErrorResponse("scopeId must be a store of this organization"));
            case "BRANCH_GROUP":
                if (count("SELECT COUNT(*) FROM branch_groups WHERE id = ? AND organization_id = ?", scopeId, org) > 0) return null;
                return ResponseEntity.badRequest().body(new ErrorResponse("scopeId must be a branch group of this organization"));
            default:
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(
            "scopeType must be " + (user ? "COMPANY, BRANCH_GROUP or BRANCH" : "BRANCH_GROUP or BRANCH")));
    }

    private Long targetOrganization(long subjectId, boolean user) {
        List<Long> r = jdbc.query(
            user ? "SELECT organization_id FROM users WHERE id = ?" : "SELECT organization_id FROM employees WHERE id = ?",
            (rs, i) -> rs.getLong(1), subjectId);
        return r.isEmpty() ? null : r.get(0);
    }

    // A super admin (organization 0) may work on any organization's people; everyone else only on their own.
    private boolean sameOrg(UserSession session, long targetOrg) {
        return session.organizationId() <= 0 || session.organizationId() == targetOrg;
    }

    private boolean isSuperAdmin(UserSession session) {
        return sessionService.resolvePermissions(session.userId(), null).contains(Permission.MANAGE_SYSTEM.name());
    }

    private void requireAny(UserSession session, Permission a, Permission b) {
        Set<String> perms = sessionService.resolvePermissionsForOrg(session.userId(), session.organizationId());
        if (!perms.contains(a.name()) && !perms.contains(b.name()))
            throw new com.waha.common.ForbiddenException("Permission denied: " + a.name());
    }

    private void writePermissions(long roleId, Set<Permission> perms) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
        for (Permission p : perms)
            jdbc.update("INSERT INTO role_permissions (role_id, permission) VALUES (?,?)", roleId, p.name());
    }

    private Map<Long, Integer> assignmentCounts() {
        Map<Long, Integer> out = new HashMap<>();
        for (String t : List.of("user_roles", "employee_roles"))
            jdbc.query("SELECT role_id, COUNT(*) FROM " + t + " GROUP BY role_id",
                rs -> { out.merge(rs.getLong(1), rs.getInt(2), Integer::sum); });
        return out;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> toMap(RoleCatalog.RoleInfo r, int assigned) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("description", r.description());
        m.put("builtIn", r.builtIn());
        m.put("fixed", r.fixed());
        m.put("organizationId", r.organizationId());
        m.put("permissions", r.permissions().stream().map(Enum::name).sorted().toList());
        m.put("assignedCount", assigned);
        return m;
    }

    private static ResponseEntity<?> forbidden(String msg) {
        return ResponseEntity.status(403).body(new ErrorResponse(msg));
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(new ErrorResponse("Not found"));
    }

    private static String text(JsonNode b, String f) {
        return b.hasNonNull(f) ? b.get(f).asText() : null;
    }

    private static String clip(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private static List<String> strings(JsonNode b, String f) {
        List<String> out = new ArrayList<>();
        if (b.has(f) && b.get(f).isArray()) b.get(f).forEach(n -> out.add(n.asText()));
        return out;
    }
}
