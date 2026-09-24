package com.waha.employee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waha.auth.Permission;
import com.waha.auth.PinLockoutService;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/employees")
public class EmployeeAdminController {

    private final EmployeeRepository employeeRepository;
    private final SessionService sessionService;
    private final BCryptPasswordEncoder encoder;
    private final PinLockoutService lockoutService;

    public EmployeeAdminController(EmployeeRepository employeeRepository, SessionService sessionService,
                                   BCryptPasswordEncoder encoder, PinLockoutService lockoutService) {
        this.employeeRepository = employeeRepository;
        this.sessionService     = sessionService;
        this.encoder            = encoder;
        this.lockoutService     = lockoutService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());
        return ResponseEntity.ok(
            employeeRepository.findAll(session.organizationId()).stream().map(this::toMap).toList()
        );
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody JsonNode body) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());

        String username = text(body, "username");
        String pinCode  = text(body, "pinCode");
        if (username == null || username.isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("username is required"));
        if (pinCode == null || pinCode.isBlank() || pinCode.length() != 6)
            return ResponseEntity.badRequest().body(new ErrorResponse("pinCode must be 6 digits"));
        if (isWeakPin(pinCode))
            return ResponseEntity.badRequest().body(new ErrorResponse("PIN is too easy to guess, choose a less predictable one"));

        long orgId = session.organizationId();
        if (employeeRepository.existsByUsername(username, orgId))
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));

        long employeeId;
        try {
            employeeId = employeeRepository.create(
                orgId,
                username,
                encoder.encode(pinCode),
                text(body, "firstName"),
                text(body, "lastName"),
                text(body, "gender"),
                localDate(body, "birthDate"),
                text(body, "email"),
                text(body, "phone"),
                localDate(body, "hiredAt"),
                text(body, "address"),
                text(body, "notes"),
                longVal(body, "avatarResourceId"),
                !body.has("enabled") || body.get("enabled").asBoolean(true)
            );
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(new ErrorResponse("Username already taken"));
        }

        // Optional role + store assignment
        ResponseEntity<?> roleError = assignRoleIfPresent(employeeId, body);
        if (roleError != null) return roleError;

        return ResponseEntity.ok(Map.of("id", employeeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());
        if (!employeeRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found: " + id));
        var stores = employeeRepository.findStores(id);
        // findAll filters by orgId — refetch from the list for simplicity
        var view = employeeRepository.findAll(session.organizationId()).stream()
            .filter(e -> e.id() == id).findFirst();
        if (view.isEmpty())
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found: " + id));
        Map<String, Object> m = toMap(view.get());
        m.put("branches", stores);
        return ResponseEntity.ok(m);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());

        if (!employeeRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found: " + id));

        JsonNode patchBody = body;
        if (body.has("pinCode")) {
            String pin = body.get("pinCode").asText("").trim();
            if (pin.length() != 6)
                return ResponseEntity.badRequest().body(new ErrorResponse("pinCode must be 6 digits"));
            if (isWeakPin(pin))
                return ResponseEntity.badRequest().body(new ErrorResponse("PIN is too easy to guess, choose a less predictable one"));
            ObjectNode copy = body.deepCopy();
            copy.put("pinCode", encoder.encode(pin));
            patchBody = copy;
            lockoutService.clearEmployeeLockout(id);
        }
        if (body.has("enabled") && body.get("enabled").asBoolean())
            lockoutService.clearEmployeeLockout(id);

        employeeRepository.patch(id, patchBody);

        if (body.has("role") || body.has("storeId")) {
            employeeRepository.clearRoles(id);
            ResponseEntity<?> roleError = assignRoleIfPresent(id, body);
            if (roleError != null) return roleError;
        }

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {

        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());

        if (!employeeRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found: " + id));

        employeeRepository.delete(id);
        return ResponseEntity.ok().build();
    }

    // ── store assignment ──────────────────────────────────────────────────────

    @PostMapping("/{id}/stores")
    public ResponseEntity<?> addStore(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());
        if (!employeeRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found: " + id));
        long storeId = body.has("storeId") ? body.get("storeId").asLong(0) : 0L;
        if (storeId <= 0) return ResponseEntity.badRequest().body(new ErrorResponse("storeId is required"));
        employeeRepository.addStore(id, storeId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/stores/{storeId}")
    public ResponseEntity<?> removeStore(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @PathVariable long storeId) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_EMPLOYEES, session.organizationId());
        if (!employeeRepository.existsById(id))
            return ResponseEntity.status(404).body(new ErrorResponse("Employee not found: " + id));
        employeeRepository.removeStore(id, storeId);
        return ResponseEntity.ok().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<?> assignRoleIfPresent(long employeeId, JsonNode body) {
        if (!body.has("role")) return null;
        String roleName = body.get("role").asText("").toUpperCase();
        // Valid POS roles only — SUPER_ADMIN and ORGANIZATION_OWNER belong in users/user_roles
        if (!java.util.Set.of("BRANCH_ADMIN", "CASHIER", "OPERATOR").contains(roleName)) return null;

        long storeId = body.has("storeId") ? body.get("storeId").asLong(0) : 0L;
        String scopeType = "BRANCH";

        if (storeId <= 0) return null;

        Long roleId = employeeRepository.roleIdByName(roleName);
        if (roleId == null) return ResponseEntity.badRequest().body(new ErrorResponse("Unknown role: " + roleName));

        employeeRepository.assignRole(employeeId, roleId, scopeType, storeId);
        employeeRepository.addStore(employeeId, storeId);
        return null;
    }

    private Map<String, Object> toMap(EmployeeRepository.EmployeeAdminView e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                e.id());
        m.put("username",          e.username());
        m.put("firstName",         e.firstName());
        m.put("lastName",          e.lastName());
        m.put("gender",            e.gender());
        m.put("birthDate",         e.birthDate() != null ? e.birthDate().toString() : null);
        m.put("email",             e.email());
        m.put("phone",             e.phone());
        m.put("hiredAt",           e.hiredAt() != null ? e.hiredAt().toString() : null);
        m.put("address",           e.address());
        m.put("notes",             e.notes());
        m.put("avatarResourceId",  e.avatarResourceId());
        m.put("enabled",           e.enabled());
        m.put("createdAt",         e.createdAt() != null ? e.createdAt().toString() : null);
        m.put("roleName",          e.roleName());
        m.put("storeId",           e.storeId());
        m.put("storeName",         e.storeName());
        m.put("lockedUntil",       e.lockedUntil() != null ? e.lockedUntil().toString() : null);
        return m;
    }

    private static boolean isWeakPin(String pin) {
        if (pin.chars().distinct().count() == 1) return true; // 000000, 111111, …
        boolean asc = true, desc = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != 1) asc = false;
            if (pin.charAt(i - 1) - pin.charAt(i) != 1) desc = false;
        }
        if (asc || desc) return true; // 123456, 987654, …
        // repeating pairs: 112233, 445566, …
        if (pin.charAt(0) == pin.charAt(1) && pin.charAt(2) == pin.charAt(3) && pin.charAt(4) == pin.charAt(5)) return true;
        return java.util.Set.of("123123", "121212", "111222", "222333", "333444",
            "444555", "555666", "666777", "777888", "888999", "123321", "654321").contains(pin);
    }

    private static String text(JsonNode body, String field) {
        return body.has(field) && !body.get(field).isNull() ? body.get(field).asText(null) : null;
    }

    private static LocalDate localDate(JsonNode body, String field) {
        String v = text(body, field);
        return v != null ? LocalDate.parse(v) : null;
    }

    private static Long longVal(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) return null;
        return body.get(field).asLong();
    }
}
