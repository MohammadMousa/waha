package com.waha.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ForbiddenException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

// The signed-in person's own profile (first and last name, phone, email, gender, birth date, language, picture).
// Admins work on other accounts through AdminProfileController.
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final SessionService sessionService;
    private final ProfileService profiles;

    public ProfileController(SessionService sessionService, ProfileService profiles) {
        this.sessionService = sessionService;
        this.profiles = profiles;
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession s = userSession(auth);
        return ResponseEntity.ok(profiles.find(s.userId()).orElseThrow(() -> new ForbiddenException("Permission denied")));
    }

    // Body: any of firstName, lastName, phone, email, gender (MALE|FEMALE), birthDate (yyyy-MM-dd), language (ar|en).
    // A field that is missing stays as it is; a field sent empty or null is cleared. Changing the phone clears its verified mark.
    @PutMapping
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody JsonNode body) {
        return profiles.update(userSession(auth).userId(), body);
    }

    // Multipart field "file": a JPEG or PNG up to 5 MB, saved as a 256 x 256 JPEG.
    @PutMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @RequestParam("file") MultipartFile file) {
        return profiles.uploadAvatar(userSession(auth).userId(), file);
    }

    @DeleteMapping("/avatar")
    public ResponseEntity<?> deleteAvatar(@RequestHeader(value = "Authorization", required = false) String auth) {
        return profiles.deleteAvatar(userSession(auth).userId());
    }

    private UserSession userSession(String auth) {
        UserSession s = sessionService.requireSession(auth);
        if (!s.isUserSession()) throw new ForbiddenException("Permission denied");
        return s;
    }
}
