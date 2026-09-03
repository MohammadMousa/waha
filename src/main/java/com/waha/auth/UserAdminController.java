package com.waha.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private static final Set<String> VALID_TYPES = Set.of("HUMAN", "KIOSK", "SYSTEM");
    private static final Set<String> VALID_ROLES = Set.of(
        "SUPER_ADMIN", "ADMIN", "OPERATOR", "CASHIER", "REGISTERED", "ANONYMOUS"
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

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String accountType) {

        sessionService.requirePermission(auth, Permission.MANAGE_USERS, 1L);
        return ResponseEntity.ok(userRepository.findAll(accountType).stream().map(this::toMap).toList());
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody JsonNode body) {

        sessionService.requirePermission(auth, Permission.MANAGE_USERS, 1L);

        String username = body.has("username") ? body.get("username").asText("").trim() : "";
        String password = body.has("password") ? body.get("password").asText("").trim() : "";
        if (username.isEmpty() || password.isEmpty())
            return ResponseEntity.badRequest().body(new ErrorResponse("username and password are required"));

        if (userRepository.existsByUsername(username))
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));

        String accountType = body.has("accountType") ? body.get("accountType").asText("HUMAN") : "HUMAN";
        if (!VALID_TYPES.contains(accountType))
            return ResponseEntity.badRequest().body(new ErrorResponse("accountType must be one of: " + VALID_TYPES));

        boolean enabled   = !body.has("enabled") || body.get("enabled").asBoolean(true);
        String firstName  = body.has("firstName") ? body.get("firstName").asText(null) : null;
        String lastName   = body.has("lastName")  ? body.get("lastName").asText(null)  : null;
        String phone      = body.has("phone")     ? body.get("phone").asText(null)     : null;

        long userId = userRepository.createAccount(
            username, passwordEncoder.encode(password),
            accountType, enabled, firstName, lastName, phone
        );

        assignRoleIfPresent(userId, body);

        return ResponseEntity.ok(Map.of("id", userId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {

        sessionService.requirePermission(auth, Permission.MANAGE_USERS, 1L);

        if (!userRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found: " + id));

        if (body.has("password")) {
            String pw = body.get("password").asText("").trim();
            if (!pw.isEmpty()) userRepository.updatePassword(id, passwordEncoder.encode(pw));
        }

        userRepository.patch(id, body);

        if (body.has("role") && body.has("storeId")) {
            String roleName = body.get("role").asText("").toUpperCase();
            long storeId = body.get("storeId").asLong(0);
            if (VALID_ROLES.contains(roleName) && storeId > 0) {
                for (Role r : Role.values()) {
                    try { roleRepository.removeRole(id, r, storeId); } catch (Exception ignored) {}
                }
                roleRepository.assignRole(id, Role.valueOf(roleName), storeId);
            }
        }

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {

        sessionService.requirePermission(auth, Permission.MANAGE_USERS, 1L);

        if (!userRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found: " + id));

        userRepository.delete(id);
        return ResponseEntity.ok().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assignRoleIfPresent(long userId, JsonNode body) {
        if (!body.has("role") || !body.has("storeId")) return;
        String roleName = body.get("role").asText("").toUpperCase();
        long storeId = body.get("storeId").asLong(0);
        if (VALID_ROLES.contains(roleName) && storeId > 0) {
            roleRepository.assignRole(userId, Role.valueOf(roleName), storeId);
        }
    }

    private Map<String, Object> toMap(UserRepository.UserAdminView u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("accountType", u.accountType());
        m.put("enabled", u.enabled());
        m.put("firstName", u.firstName());
        m.put("lastName", u.lastName());
        m.put("phone", u.phone());
        m.put("createdAt", u.createdAt() != null ? u.createdAt().toString() : null);
        m.put("roleName", u.roleName());
        m.put("storeId", u.storeId());
        m.put("storeName", u.storeName());
        m.put("lastLoginAt", u.lastLoginAt() != null ? u.lastLoginAt().toString() : null);
        return m;
    }
}
