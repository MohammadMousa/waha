package com.waha.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.audit.AuditService;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// An admin reads and changes another account's profile and picture. Needs MANAGE_USERS (the same permission as the
// accounts screen), and the account must belong to the admin's organization unless the caller is a super admin
// working without one. Each change is recorded in the audit history (field names only, never the values).
@RestController
@RequestMapping("/api/admin/users/{id}")
public class AdminProfileController {

    private final SessionService sessionService;
    private final ProfileService profiles;
    private final AuditService audit;

    public AdminProfileController(SessionService sessionService, ProfileService profiles, AuditService audit) {
        this.sessionService = sessionService;
        this.profiles = profiles;
        this.audit = audit;
    }

    @GetMapping("/profile")
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id) {
        ResponseEntity<?> denied = check(auth, id);
        return denied != null ? denied : ResponseEntity.ok(profiles.find(id).orElseThrow());
    }

    @PutMapping("/profile")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id, @RequestBody JsonNode body) {
        ResponseEntity<?> denied = check(auth, id);
        if (denied != null) return denied;
        ResponseEntity<?> result = profiles.update(id, body);
        if (result.getStatusCode().is2xxSuccessful()) {
            List<String> fields = new ArrayList<>();
            for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) fields.add(it.next());
            record(auth, id, "PROFILE_UPDATED", "Fields: " + String.join(", ", fields));
        }
        return result;
    }

    @PutMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable long id, @RequestParam("file") MultipartFile file) {
        ResponseEntity<?> denied = check(auth, id);
        if (denied != null) return denied;
        ResponseEntity<?> result = profiles.uploadAvatar(id, file);
        if (result.getStatusCode().is2xxSuccessful()) record(auth, id, "AVATAR_UPDATED", null);
        return result;
    }

    @DeleteMapping("/avatar")
    public ResponseEntity<?> deleteAvatar(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable long id) {
        ResponseEntity<?> denied = check(auth, id);
        if (denied != null) return denied;
        ResponseEntity<?> result = profiles.deleteAvatar(id);
        if (result.getStatusCode().is2xxSuccessful()) record(auth, id, "AVATAR_REMOVED", null);
        return result;
    }

    // Null when allowed; otherwise the answer to send.
    private ResponseEntity<?> check(String auth, long userId) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.MANAGE_USERS, session.organizationId());
        Long target = profiles.organizationOf(userId).orElse(null);
        if (target == null || (session.organizationId() > 0 && target != session.organizationId()))
            return ResponseEntity.status(404).body(new ErrorResponse("User not found: " + userId));
        return null;
    }

    private void record(String auth, long userId, String action, String details) {
        UserSession session = sessionService.requireSession(auth);
        long org = profiles.organizationOf(userId).orElse(0L);
        audit.record(org > 0 ? AuditService.ORGANIZATION : AuditService.SYSTEM, Math.max(org, 0), "USER", String.valueOf(userId),
            session.userId(), null, action, "user " + userId, null, null, details);
    }
}
