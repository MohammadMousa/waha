package com.waha.address;

import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.regex.Pattern;

// A customer's own saved addresses (any signed-in user; devices and employees have none). Nobody sees another user's addresses.
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    static final int MAX_ADDRESSES = 20;
    private static final Pattern PHONE = Pattern.compile("^[+0-9 ()-]{6,30}$");

    private final AddressRepository repo;
    private final SessionService sessionService;

    public AddressController(AddressRepository repo, SessionService sessionService) {
        this.repo = repo;
        this.sessionService = sessionService;
    }

    private long userId(String auth) {
        UserSession session = sessionService.requireSession(auth);
        if (!session.isUserSession()) throw new ForbiddenException("Addresses belong to user accounts");
        return session.userId();
    }

    private static ResponseEntity<?> notFound(long id) {
        return ResponseEntity.status(404).body(new ErrorResponse("Address not found: " + id));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        return ResponseEntity.ok(repo.list(userId(auth)));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody AddressRequest raw) {
        long user = userId(auth);
        AddressRequest body = clean(raw);
        String error = validate(body);
        if (error != null) return ResponseEntity.badRequest().body(new ErrorResponse(error));
        int have = repo.count(user);
        if (have >= MAX_ADDRESSES)
            return ResponseEntity.status(409).body(new ErrorResponse("You can keep up to " + MAX_ADDRESSES + " addresses, delete one first"));
        boolean makeDefault = have == 0 || Boolean.TRUE.equals(body.isDefault());
        long id = repo.insert(user, body, country(body), makeDefault);
        return ResponseEntity.status(201).body(repo.find(user, id).orElseThrow());
    }

    // Replaces the editable fields; send the whole address.
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id, @RequestBody AddressRequest raw) {
        long user = userId(auth);
        AddressRequest body = clean(raw);
        if (repo.find(user, id).isEmpty()) return notFound(id);
        String error = validate(body);
        if (error != null) return ResponseEntity.badRequest().body(new ErrorResponse(error));
        repo.update(user, id, body, country(body), Boolean.TRUE.equals(body.isDefault()));
        return ResponseEntity.ok(repo.find(user, id).orElseThrow());
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<?> makeDefault(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @PathVariable long id) {
        long user = userId(auth);
        if (repo.find(user, id).isEmpty()) return notFound(id);
        repo.setDefault(user, id);
        return ResponseEntity.ok(repo.find(user, id).orElseThrow());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id) {
        long user = userId(auth);
        if (repo.find(user, id).isEmpty()) return notFound(id);
        repo.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    private static String country(AddressRequest r) {
        return r.countryCode() == null || r.countryCode().isBlank() ? "SA" : r.countryCode().trim().toUpperCase();
    }

    // Trims every text and turns blanks into nothing.
    static AddressRequest clean(AddressRequest r) {
        if (r == null) return null;
        return new AddressRequest(t(r.label()), t(r.recipientName()), t(r.phone()), t(r.buildingNumber()), t(r.streetName()),
            t(r.secondaryNumber()), t(r.district()), t(r.city()), t(r.postalCode()), t(r.countryCode()), t(r.additionalInfo()),
            r.isDefault());
    }

    private static String t(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // Null when valid, otherwise the message to show.
    static String validate(AddressRequest r) {
        if (r == null) return "Address is required";
        if (blank(r.city())) return "city is required";
        if (blank(r.streetName())) return "streetName is required";
        if (blank(r.phone())) return "phone is required";
        if (!PHONE.matcher(r.phone().trim()).matches()) return "phone is not a valid number";
        if (tooLong(r.label(), 60)) return "label is too long (60 at most)";
        if (tooLong(r.recipientName(), 255) || tooLong(r.streetName(), 255) || tooLong(r.district(), 255)
            || tooLong(r.city(), 255) || tooLong(r.additionalInfo(), 255)) return "A field is too long (255 at most)";
        if (tooLong(r.buildingNumber(), 10) || tooLong(r.secondaryNumber(), 10) || tooLong(r.postalCode(), 10))
            return "buildingNumber, secondaryNumber and postalCode take 10 characters at most";
        if (r.countryCode() != null && !r.countryCode().isBlank() && !r.countryCode().trim().matches("^[A-Za-z]{2}$"))
            return "countryCode must be 2 letters like SA";
        return null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean tooLong(String s, int max) { return s != null && s.trim().length() > max; }
}
