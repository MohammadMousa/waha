package com.waha.profile;

import java.time.LocalDate;
import java.util.regex.Pattern;

// Pure validation and cleaning of profile fields. Every method returns the value to save (null clears it)
// or throws IllegalArgumentException with a message the client can show.
final class ProfileRules {

    private ProfileRules() {}

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern LANG = Pattern.compile("^[a-z]{2}(-[A-Za-z]{2})?$");

    static String name(String field, String raw) {
        String v = blankToNull(raw);
        if (v == null) return null;
        if (v.length() > 100) throw new IllegalArgumentException(field + " is too long (100 characters at most)");
        return v;
    }

    static String email(String raw) {
        String v = blankToNull(raw);
        if (v == null) return null;
        v = v.toLowerCase();
        if (v.length() > 255 || !EMAIL.matcher(v).matches()) throw new IllegalArgumentException("email is not a valid address");
        return v;
    }

    static String gender(String raw) {
        String v = blankToNull(raw);
        if (v == null) return null;
        v = v.toUpperCase();
        if (!v.equals("MALE") && !v.equals("FEMALE")) throw new IllegalArgumentException("gender must be MALE or FEMALE");
        return v;
    }

    static LocalDate birthDate(String raw, LocalDate today) {
        String v = blankToNull(raw);
        if (v == null) return null;
        LocalDate d;
        try {
            d = LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("birthDate must be a date like 1990-05-17");
        }
        if (d.isAfter(today) || d.isBefore(LocalDate.of(1900, 1, 1)))
            throw new IllegalArgumentException("birthDate is not a valid date of birth");
        return d;
    }

    static String language(String raw) {
        String v = blankToNull(raw);
        if (v == null) return null;
        if (!LANG.matcher(v).matches()) throw new IllegalArgumentException("language must be like ar or en");
        return v;
    }

    // One standard form (+9665...) so the same number cannot be saved twice in different spellings.
    // A number with no country code gets defaultCountryCode (digits only, for example "966").
    static String phone(String raw, String defaultCountryCode) {
        String v = blankToNull(raw);
        if (v == null) return null;
        String s = v.replaceAll("[\\s\\-().]", "");
        String normalized;
        if (s.startsWith("+")) {
            normalized = s;
        } else if (s.startsWith("00")) {
            normalized = "+" + s.substring(2);
        } else if (s.startsWith("0")) {
            normalized = "+" + defaultCountryCode + s.substring(1);
        } else if (s.matches("[0-9]+")) {
            if (s.startsWith(defaultCountryCode) && s.length() > 10) normalized = "+" + s;
            else if (s.length() <= 10) normalized = "+" + defaultCountryCode + s;
            else normalized = "+" + s;
        } else {
            throw new IllegalArgumentException("phone is not a valid number");
        }
        if (!PHONE.matcher(normalized).matches()) throw new IllegalArgumentException("phone is not a valid number");
        return normalized;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
