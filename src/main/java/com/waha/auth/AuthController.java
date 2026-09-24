package com.waha.auth;

import com.waha.auth.dto.*;
import com.waha.common.ErrorResponse;
import com.waha.common.UnauthorizedException;
import com.waha.store.StoreRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final List<String> VALID_MODES = List.of("NORMAL", "KIOSK", "SHOPPING");

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final StoreRepository storeRepository;
    private final RoleRepository roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthController(UserRepository userRepository, SessionService sessionService,
                          StoreRepository storeRepository, RoleRepository roleRepository,
                          BCryptPasswordEncoder passwordEncoder) {
        this.userRepository  = userRepository;
        this.sessionService  = sessionService;
        this.storeRepository = storeRepository;
        this.roleRepository  = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    private Long defaultStoreId(long orgId) {
        return storeRepository.findDefaultStoreId(orgId).orElse(null);
    }

    private Map<String, String> systemProperties(long orgId) {
        return storeRepository.findAllProperties(orgId);
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // Highest-privilege role the user holds at the given store (or system-wide).
    private String primaryRoleName(long userId, Long storeId) {
        return roleRepository.resolveRoleNames(userId, storeId).stream()
            .map(name -> { try { return Role.valueOf(name); } catch (IllegalArgumentException e) { return null; } })
            .filter(java.util.Objects::nonNull)
            .min(java.util.Comparator.comparingInt(Role::ordinal))
            .map(Enum::name)
            .orElse(null);
    }

    private String extractMode(Map<String, String> props) {
        if (props == null) return null;
        String mode = props.get("mode");
        if (mode == null) return null;
        String upper = mode.toUpperCase();
        if (!VALID_MODES.contains(upper)) return "__INVALID__";
        return upper;
    }

    // Returns "KIOSK" if the user's only applicable role at this store is KIOSK.
    // Staff users (CASHIER and above) who also have a KIOSK role are not auto-detected —
    // they choose their mode explicitly when provisioning a device.
    private String detectAutoMode(long userId, Long storeId) {
        if (storeId == null) return null;
        List<String> roleNames = roleRepository.resolveRoleNames(userId, storeId);
        if (!roleNames.contains(Role.KIOSK.name())) return null;
        boolean hasStaffRole = roleNames.stream().anyMatch(name -> {
            try { return Role.valueOf(name).includes(Role.CASHIER); }
            catch (IllegalArgumentException e) { return false; }
        });
        return hasStaffRole ? null : "KIOSK";
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (request.username() == null || request.username().isBlank() || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.status(400).body(new ErrorResponse("username and password are required"));
        }
        long orgId = request.organizationId() != null ? request.organizationId() : 1L;
        if (userRepository.existsByUsername(request.username(), orgId)) {
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));
        }

        String mode = extractMode(request.sessionProperties());
        if ("__INVALID__".equals(mode)) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode must be one of: NORMAL, KIOSK, SHOPPING"));
        }

        String hash = passwordEncoder.encode(request.password());
        long userId = userRepository.create(request.username(), hash, orgId);
        String token = sessionService.createSession(userId);
        if (mode != null) sessionService.setMode(token, mode);
        Long defStore = defaultStoreId(orgId);
        if (defStore != null) sessionService.setStore(token, defStore);

        Set<String> permissions = sessionService.resolvePermissions(userId, defStore);
        return ResponseEntity.ok(new AuthResponse(token, userId, request.username(), defStore, defStore, mode, systemProperties(orgId), primaryRoleName(userId, defStore), permissions));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        var record = userRepository.findPasswordRecord(
            request.username() == null ? "" : request.username(),
            request.organizationId()
        );

        boolean valid = record.isPresent()
            && passwordEncoder.matches(request.password() == null ? "" : request.password(), record.get().passwordHash());

        if (!valid) {
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid username or password"));
        }

        String mode = extractMode(request.sessionProperties());
        if ("__INVALID__".equals(mode)) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode must be one of: NORMAL, KIOSK, SHOPPING"));
        }

        long loginOrgId = request.organizationId() != null ? request.organizationId() : 1L;
        String token = sessionService.createSession(record.get().id());
        // Prefer the user's own BRANCH role assignment; fall back to the org's default store.
        Long defStore = roleRepository.findAssignedBranchStoreId(record.get().id())
            .orElseGet(() -> defaultStoreId(loginOrgId));
        if (defStore != null) sessionService.setStore(token, defStore);

        // Explicit mode wins; fall back to role-based auto-detection (KIOSK devices)
        if (mode == null) mode = detectAutoMode(record.get().id(), defStore);
        if (mode != null) sessionService.setMode(token, mode);

        long loginUserId = record.get().id();
        Set<String> permissions = sessionService.resolvePermissions(loginUserId, defStore);
        return ResponseEntity.ok(new AuthResponse(token, loginUserId, record.get().username(), defStore, defStore, mode, systemProperties(loginOrgId), primaryRoleName(loginUserId, defStore), permissions));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<?> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> body) {
        try {
            UserSession session = sessionService.requireSession(authHeader);
            String currentPassword = body.getOrDefault("currentPassword", "").trim();
            String newPassword     = body.getOrDefault("newPassword",     "").trim();
            if (currentPassword.isBlank() || newPassword.isBlank())
                return ResponseEntity.badRequest().body(new ErrorResponse("currentPassword and newPassword are required"));
            if (newPassword.length() < 8)
                return ResponseEntity.badRequest().body(new ErrorResponse("newPassword must be at least 8 characters"));

            Optional<String> currentHash = userRepository.findPasswordHashById(session.userId());
            if (currentHash.isEmpty() || !passwordEncoder.matches(currentPassword, currentHash.get()))
                return ResponseEntity.status(401).body(new ErrorResponse("Current password is incorrect"));

            userRepository.updatePassword(session.userId(), passwordEncoder.encode(newPassword));
            return ResponseEntity.ok().build();
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            UserSession session = sessionService.requireSession(authHeader);
            sessionService.deleteSession(session.token());
            return ResponseEntity.ok().build();
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            UserSession session = sessionService.requireSession(authHeader);
            User user = userRepository.findById(session.userId())
                .orElseThrow(() -> new UnauthorizedException("Missing or invalid session"));
            Set<String> permissions = sessionService.resolvePermissions(session.userId(), session.storeId());
            return ResponseEntity.ok(new MeResponse(user.id(), user.username(), session.storeId(), defaultStoreId(session.organizationId()), session.mode(), systemProperties(session.organizationId()), primaryRoleName(session.userId(), session.storeId()), permissions));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    // Most natural place to set Kiosk mode: operator logs in, then calls
    // configureStore with storeId + sessionProperties={"mode":"KIOSK"}.
    // Mode update is optional — omitting sessionProperties leaves existing mode unchanged.
    @PostMapping("/store")
    public ResponseEntity<?> selectStore(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                          @RequestBody SelectStoreRequest request) {
        try {
            UserSession session = sessionService.requireSession(authHeader);

            if (request.storeId() == null) {
                return ResponseEntity.status(400).body(new ErrorResponse("storeId is required"));
            }
            Set<String> currentPerms = sessionService.resolvePermissions(session.userId(), session.storeId());
            boolean canSelect = currentPerms.contains(Permission.MANAGE_STORES.name())
                ? storeRepository.isAdminSelectable(request.storeId())
                : storeRepository.isSelectable(request.storeId());
            if (!canSelect) {
                return ResponseEntity.status(400).body(new ErrorResponse("storeId is not a valid store"));
            }

            String mode = extractMode(request.sessionProperties());
            if ("__INVALID__".equals(mode)) {
                return ResponseEntity.status(400).body(new ErrorResponse("mode must be one of: NORMAL, KIOSK, SHOPPING"));
            }

            sessionService.setStore(session.token(), request.storeId());
            if (mode != null) sessionService.setMode(session.token(), mode);

            String effectiveMode = mode != null ? mode : session.mode();

            User user = userRepository.findById(session.userId())
                .orElseThrow(() -> new UnauthorizedException("Missing or invalid session"));

            // Permissions re-resolved at the newly selected store
            Set<String> permissions = sessionService.resolvePermissions(session.userId(), request.storeId());
            return ResponseEntity.ok(new MeResponse(user.id(), user.username(), request.storeId(), defaultStoreId(session.organizationId()), effectiveMode, systemProperties(session.organizationId()), primaryRoleName(session.userId(), request.storeId()), permissions));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    // Guest sessions are always SHOPPING mode — that's the only mode that
    // allows anonymous browsing without prior registration.
    @PostMapping("/guest")
    public ResponseEntity<?> guest() {
        String username = "guest-" + randomHex(8);
        String passwordHash = passwordEncoder.encode(randomHex(16));
        long userId = userRepository.create(username, passwordHash, 1L);
        String token = sessionService.createSession(userId);
        sessionService.setMode(token, "SHOPPING");

        Long defStore = defaultStoreId(1L);
        if (defStore != null) {
            sessionService.setStore(token, defStore);
        }

        // Guests get ANONYMOUS permissions (no user_roles row needed)
        Set<String> permissions = sessionService.resolvePermissions(userId, defStore);
        return ResponseEntity.ok(new AuthResponse(token, userId, username, defStore, defStore, "SHOPPING", systemProperties(1L), null, permissions));
    }
}
