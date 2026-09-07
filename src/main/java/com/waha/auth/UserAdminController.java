package com.waha.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.common.ErrorResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    // users table = admin-app identities only: platform owner or org owner.
    // Branch-level staff (BRANCH_ADMIN, OPERATOR, CASHIER) are employees, not users.
    private static final Set<String> VALID_ROLES = Set.of(
        "SUPER_ADMIN", "ORGANIZATION_OWNER"
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SessionService sessionService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserAdminController(UserRepository userRepository, RoleRepository roleRepository,
                               SessionService sessionService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.sessionService = sessionService;
    }

    @GetMapping("/roles")
    public ResponseEntity<?> assignableRoles(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());
        boolean isSuperAdmin = sessionService.resolvePermissions(session.userId(), null)
            .contains(Permission.MANAGE_SYSTEM.name());
        List<String> roles = isSuperAdmin
            ? List.of("SUPER_ADMIN", "ORGANIZATION_OWNER")
            : List.of("ORGANIZATION_OWNER");
        return ResponseEntity.ok(roles);
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String role) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());
        return ResponseEntity.ok(userRepository.findAll(role, session.organizationId()).stream().map(this::toMap).toList());
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody JsonNode body) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());

        String username = body.has("username") ? body.get("username").asText("").trim() : "";
        String password = body.has("password") ? body.get("password").asText("").trim() : "";
        if (username.isEmpty() || password.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("username and password are required"));

        long orgId = body.has("organizationId") ? body.get("organizationId").asLong(1L) : 1L;
        if (userRepository.existsByUsername(username, orgId))
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));

        long userId = userRepository.create(username, passwordEncoder.encode(password), orgId);

        ResponseEntity<?> roleError = assignRoleIfPresent(userId, body, orgId, session);
        if (roleError != null) return roleError;

        return ResponseEntity.ok(Map.of("id", userId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());

        if (!userRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found: " + id));

        if (body.has("password")) {
            String pw = body.get("password").asText("").trim();
            if (!pw.isEmpty()) userRepository.updatePassword(id, passwordEncoder.encode(pw));
        }

        if (body.has("role")) {
            String roleName = body.get("role").asText("").toUpperCase();
            if (VALID_ROLES.contains(roleName)) {
                Role role = Role.valueOf(roleName);
                boolean callerIsSuperAdmin = sessionService.resolvePermissions(session.userId(), null)
                    .contains(Permission.MANAGE_SYSTEM.name());
                if (role == Role.SUPER_ADMIN && !callerIsSuperAdmin) {
                    return ResponseEntity.status(403).body(new ErrorResponse("Only SUPER_ADMIN can assign the SUPER_ADMIN role"));
                }
                boolean isSystem  = role == Role.SUPER_ADMIN;
                boolean isCompany = role == Role.ORGANIZATION_OWNER;
                long scopeId = isSystem ? 0L
                    : isCompany ? session.organizationId()
                    : (body.has("storeId") ? body.get("storeId").asLong(0) : 0L);
                if (isSystem || isCompany || scopeId > 0) {
                    for (Role r : Role.values()) {
                        try { roleRepository.removeRole(id, r, scopeId); } catch (Exception ignored) {}
                    }
                    try {
                        roleRepository.assignRole(id, role, scopeId);
                    } catch (DataIntegrityViolationException e) {
                        return ResponseEntity.badRequest().body(new ErrorResponse(extractTriggerMessage(e)));
                    }
                }
            }
        }

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());

        if (!userRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found: " + id));

        userRepository.delete(id);
        return ResponseEntity.ok().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<?> assignRoleIfPresent(long userId, JsonNode body, long orgId, UserSession caller) {
        if (!body.has("role")) return null;
        String roleName = body.get("role").asText("").toUpperCase();
        if (!VALID_ROLES.contains(roleName)) return null;
        Role role = Role.valueOf(roleName);

        // Only SUPER_ADMIN can assign SUPER_ADMIN — org-owners cannot elevate to platform level.
        boolean callerIsSuperAdmin = sessionService.resolvePermissions(caller.userId(), null)
            .contains(Permission.MANAGE_SYSTEM.name());
        if (role == Role.SUPER_ADMIN && !callerIsSuperAdmin) {
            return ResponseEntity.status(403).body(new ErrorResponse("Only SUPER_ADMIN can assign the SUPER_ADMIN role"));
        }
        boolean isSystem  = role == Role.SUPER_ADMIN;
        boolean isCompany = role == Role.ORGANIZATION_OWNER;
        long scopeId = isSystem ? 0L
            : isCompany ? orgId
            : (body.has("storeId") ? body.get("storeId").asLong(0) : 0L);
        if (!isSystem && !isCompany && scopeId <= 0) return null;
        try {
            roleRepository.assignRole(userId, role, scopeId);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(extractTriggerMessage(e)));
        }
        return null;
    }

    private static String extractTriggerMessage(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof java.sql.SQLException sqlEx && sqlEx.getErrorCode() == 1644) {
            return sqlEx.getMessage();
        }
        return e.getMessage();
    }

    private Map<String, Object> toMap(UserRepository.UserAdminView u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("createdAt", u.createdAt() != null ? u.createdAt().toString() : null);
        m.put("roleName", u.roleName());
        m.put("storeId", u.storeId());
        m.put("storeName", u.storeName());
        m.put("lastLoginAt", u.lastLoginAt() != null ? u.lastLoginAt().toString() : null);
        return m;
    }
}
