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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final List<String> VALID_MODES = List.of("NORMAL", "KIOSK", "SHOPPING");

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final StoreRepository storeRepository;
    private final RoleRepository roleRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthController(UserRepository userRepository, SessionService sessionService,
                          StoreRepository storeRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.storeRepository = storeRepository;
        this.roleRepository = roleRepository;
    }

    private Long defaultStoreId() {
        return storeRepository.findDefaultStoreId().orElse(null);
    }

    private Map<String, String> systemProperties() {
        return storeRepository.findAllProperties();
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String extractMode(Map<String, String> props) {
        if (props == null) return null;
        String mode = props.get("mode");
        if (mode == null) return null;
        String upper = mode.toUpperCase();
        if (!VALID_MODES.contains(upper)) return "__INVALID__";
        return upper;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (request.username() == null || request.username().isBlank() || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.status(400).body(new ErrorResponse("username and password are required"));
        }
        if (userRepository.existsByUsername(request.username())) {
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));
        }

        String mode = extractMode(request.sessionProperties());
        if ("__INVALID__".equals(mode)) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode must be one of: NORMAL, KIOSK, SHOPPING"));
        }

        String hash = passwordEncoder.encode(request.password());
        long userId = userRepository.create(request.username(), hash);
        String token = sessionService.createSession(userId);
        if (mode != null) sessionService.setMode(token, mode);
        Long defStore = defaultStoreId();
        if (defStore != null) {
            roleRepository.assignRole(userId, Role.REGISTERED, defStore);
            sessionService.setStore(token, defStore);
        }

        Set<String> permissions = sessionService.resolvePermissions(userId, defStore);
        return ResponseEntity.ok(new AuthResponse(token, userId, request.username(), defStore, defStore, mode, systemProperties(), permissions));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        var record = userRepository.findPasswordRecord(request.username() == null ? "" : request.username());

        boolean valid = record.isPresent()
            && passwordEncoder.matches(request.password() == null ? "" : request.password(), record.get().passwordHash());

        if (!valid) {
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid username or password"));
        }

        String mode = extractMode(request.sessionProperties());
        if ("__INVALID__".equals(mode)) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode must be one of: NORMAL, KIOSK, SHOPPING"));
        }

        String token = sessionService.createSession(record.get().id());
        if (mode != null) sessionService.setMode(token, mode);
        Long defStore = defaultStoreId();
        if (defStore != null) sessionService.setStore(token, defStore);

        Set<String> permissions = sessionService.resolvePermissions(record.get().id(), defStore);
        return ResponseEntity.ok(new AuthResponse(token, record.get().id(), record.get().username(), defStore, defStore, mode, systemProperties(), permissions));
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
            return ResponseEntity.ok(new MeResponse(user.id(), user.username(), session.storeId(), defaultStoreId(), session.mode(), systemProperties(), permissions));
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
            return ResponseEntity.ok(new MeResponse(user.id(), user.username(), request.storeId(), defaultStoreId(), effectiveMode, systemProperties(), permissions));
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
        long userId = userRepository.create(username, passwordHash);
        String token = sessionService.createSession(userId);
        sessionService.setMode(token, "SHOPPING");

        Long defStore = defaultStoreId();
        if (defStore != null) {
            sessionService.setStore(token, defStore);
        }

        // Guests get ANONYMOUS permissions (no user_roles row needed)
        Set<String> permissions = sessionService.resolvePermissions(userId, defStore);
        return ResponseEntity.ok(new AuthResponse(token, userId, username, defStore, defStore, "SHOPPING", systemProperties(), permissions));
    }
}
