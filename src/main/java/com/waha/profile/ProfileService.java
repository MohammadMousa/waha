package com.waha.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.common.ErrorResponse;
import com.waha.resource.ResourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Reads and changes one person's profile. Used for a person's own profile and by admins for other accounts;
// the callers decide who may do what.
@Service
public class ProfileService {

    private static final long MAX_AVATAR_UPLOAD = 5L * 1024 * 1024;

    private final NamedParameterJdbcTemplate jdbc;
    private final ResourceRepository resources;
    private final String defaultCountryCode;

    public ProfileService(NamedParameterJdbcTemplate jdbc, ResourceRepository resources,
                          @Value("${waha.default-country-code:966}") String defaultCountryCode) {
        this.jdbc = jdbc;
        this.resources = resources;
        this.defaultCountryCode = defaultCountryCode;
    }

    public boolean userExists(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id", new MapSqlParameterSource("id", userId), Integer.class);
        return n != null && n > 0;
    }

    public Optional<Long> organizationOf(long userId) {
        List<Long> r = jdbc.query("SELECT organization_id FROM users WHERE id = :id", new MapSqlParameterSource("id", userId),
            (rs, i) -> rs.getLong(1));
        return r.stream().findFirst();
    }

    public Optional<Map<String, Object>> find(long userId) {
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT u.username, p.first_name, p.last_name, p.phone, p.phone_verified_at, p.email, p.gender, p.birth_date, "
                + "p.language, p.avatar_resource_id FROM users u LEFT JOIN user_profiles p ON p.user_id = u.id WHERE u.id = :id",
            new MapSqlParameterSource("id", userId), (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", userId);
                m.put("username", rs.getString("username"));
                m.put("firstName", rs.getString("first_name"));
                m.put("lastName", rs.getString("last_name"));
                m.put("phone", rs.getString("phone"));
                Timestamp v = rs.getTimestamp("phone_verified_at");
                m.put("phoneVerified", v != null);
                m.put("email", rs.getString("email"));
                m.put("gender", rs.getString("gender"));
                Date b = rs.getDate("birth_date");
                m.put("birthDate", b == null ? null : b.toLocalDate().toString());
                m.put("language", rs.getString("language"));
                long av = rs.getLong("avatar_resource_id");
                m.put("avatarResourceId", rs.wasNull() ? null : av);
                return m;
            });
        return rows.stream().findFirst();
    }

    // Any of firstName, lastName, phone, email, gender, birthDate, language. A missing field stays; an empty or null field is cleared.
    public ResponseEntity<?> update(long userId, JsonNode body) {
        Map<String, Object> cur = find(userId).orElse(null);
        if (cur == null) return notFound();
        Long org = organizationOf(userId).orElse(0L);

        String first, last, phone, email, gender, language;
        LocalDate birth;
        try {
            first = body.has("firstName") ? ProfileRules.name("firstName", text(body, "firstName")) : (String) cur.get("firstName");
            last = body.has("lastName") ? ProfileRules.name("lastName", text(body, "lastName")) : (String) cur.get("lastName");
            phone = body.has("phone") ? ProfileRules.phone(text(body, "phone"), defaultCountryCode) : (String) cur.get("phone");
            email = body.has("email") ? ProfileRules.email(text(body, "email")) : (String) cur.get("email");
            gender = body.has("gender") ? ProfileRules.gender(text(body, "gender")) : (String) cur.get("gender");
            language = body.has("language") ? ProfileRules.language(text(body, "language")) : (String) cur.get("language");
            birth = body.has("birthDate") ? ProfileRules.birthDate(text(body, "birthDate"), LocalDate.now())
                : (cur.get("birthDate") == null ? null : LocalDate.parse((String) cur.get("birthDate")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        boolean phoneChanged = !java.util.Objects.equals(phone, cur.get("phone"));

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("uid", userId).addValue("org", org)
            .addValue("first", first).addValue("last", last).addValue("phone", phone).addValue("email", email)
            .addValue("gender", gender).addValue("birth", birth == null ? null : Date.valueOf(birth)).addValue("lang", language);
        try {
            // Explicit update-or-insert: an "ON DUPLICATE KEY UPDATE" would also fire on a phone or email
            // clash and overwrite another person's row.
            if (profileRowExists(userId)) {
                jdbc.update("UPDATE user_profiles SET first_name = :first, last_name = :last, phone = :phone, email = :email, "
                    + "gender = :gender, birth_date = :birth, language = :lang"
                    + (phoneChanged ? ", phone_verified_at = NULL" : "") + " WHERE user_id = :uid", params);
            } else {
                jdbc.update("INSERT INTO user_profiles (user_id, organization_id, first_name, last_name, phone, email, gender, birth_date, language) "
                    + "VALUES (:uid, :org, :first, :last, :phone, :email, :gender, :birth, :lang)", params);
            }
        } catch (DuplicateKeyException e) {
            String what = String.valueOf(e.getMessage()).contains("uq_profile_email") ? "email" : "phone number";
            return ResponseEntity.status(409).body(new ErrorResponse("This " + what + " is already used by another account"));
        }
        return ResponseEntity.ok(find(userId).orElseThrow());
    }

    // A JPEG or PNG up to 5 MB. Saved as a 256 x 256 JPEG in resources; the profile keeps its id.
    // The previous picture's file is left in place (identical files are shared by id).
    public ResponseEntity<?> uploadAvatar(long userId, MultipartFile file) {
        if (!userExists(userId)) return notFound();
        if (file.isEmpty()) return ResponseEntity.badRequest().body(new ErrorResponse("file is required"));
        if (file.getSize() > MAX_AVATAR_UPLOAD)
            return ResponseEntity.status(413).body(new ErrorResponse("The picture is larger than 5 MB"));
        byte[] jpeg;
        try {
            jpeg = AvatarImages.process(file.getBytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("The picture could not be read"));
        }
        String sha = sha256Hex(jpeg);
        long resourceId = resources.findIdBySha256(sha)
            .orElseGet(() -> resources.store("avatar.jpg", "image/jpeg", jpeg.length, sha, jpeg));
        MapSqlParameterSource p = new MapSqlParameterSource("uid", userId).addValue("rid", resourceId);
        int n = jdbc.update("UPDATE user_profiles SET avatar_resource_id = :rid WHERE user_id = :uid", p);
        if (n == 0) {
            jdbc.update("INSERT INTO user_profiles (user_id, organization_id, avatar_resource_id) VALUES (:uid, :org, :rid)",
                p.addValue("org", organizationOf(userId).orElse(0L)));
        }
        return ResponseEntity.ok(find(userId).orElseThrow());
    }

    // Removes the picture from the profile (the stored file is not deleted).
    public ResponseEntity<?> deleteAvatar(long userId) {
        if (!userExists(userId)) return notFound();
        jdbc.update("UPDATE user_profiles SET avatar_resource_id = NULL WHERE user_id = :uid", new MapSqlParameterSource("uid", userId));
        return ResponseEntity.ok(find(userId).orElseThrow());
    }

    private boolean profileRowExists(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles WHERE user_id = :uid",
            new MapSqlParameterSource("uid", userId), Integer.class);
        return n != null && n > 0;
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(new ErrorResponse("User not found"));
    }

    private static String text(JsonNode b, String f) {
        JsonNode n = b.get(f);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
