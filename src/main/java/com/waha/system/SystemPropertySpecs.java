package com.waha.system;

import java.util.LinkedHashMap;
import java.util.Map;

// The system-level settings (global, organization 0) the System Configuration screen manages, with their
// validation. Organization-level keys live in OrgPropertiesController and are not listed here.
public final class SystemPropertySpecs {

    private SystemPropertySpecs() {}

    public record Spec(String type, String defaultValue, String description, String constraints) {}

    public static final Map<String, Spec> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("publicBaseUrl", new Spec("url", "",
            "Public address of this server, used in invoice links and QR codes. Empty uses the server environment value",
            "http:// or https:// address without a trailing slash, or empty"));
        KNOWN.put("Account_Sessions_Cap", new Spec("integer", "5",
            "Most simultaneous sessions per dashboard user or employee; the oldest-activity ones end at login. 0 turns the cap off",
            "0-1000"));
        KNOWN.put("last_seen_flush_minutes", new Spec("integer", "60",
            "Minutes between database writes of last-seen times and app versions",
            "1-1440 minutes"));
        KNOWN.put("CentralProductSystem", new Spec("boolean", "true",
            "Products are managed centrally (read by the apps)", "true or false"));
        KNOWN.put("resource.max_size_bytes", new Spec("integer", "2097152",
            "Largest resource file that can be uploaded", "1024-104857600 bytes"));
    }

    // Returns the cleaned value to store, or throws IllegalArgumentException with a message for the client.
    public static String clean(String key, String raw) {
        Spec spec = KNOWN.get(key);
        if (spec == null) throw new IllegalArgumentException("Unknown system setting: " + key);
        String v = raw == null ? "" : raw.trim();
        switch (key) {
            case "publicBaseUrl" -> {
                if (v.isEmpty()) return "";
                if (v.length() > 255 || v.contains(" ") || !(v.startsWith("http://") || v.startsWith("https://")))
                    throw new IllegalArgumentException("publicBaseUrl must start with http:// or https:// and contain no spaces");
                while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
                if (v.equals("http:") || v.equals("https:") || v.endsWith("//"))
                    throw new IllegalArgumentException("publicBaseUrl must include a host");
                return v;
            }
            case "Account_Sessions_Cap" -> { return intIn(key, v, 0, 1000); }
            case "last_seen_flush_minutes" -> { return intIn(key, v, 1, 1440); }
            case "resource.max_size_bytes" -> { return intIn(key, v, 1024, 104857600); }
            case "CentralProductSystem" -> {
                String l = v.toLowerCase();
                if (!l.equals("true") && !l.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
                return l;
            }
            default -> throw new IllegalArgumentException("Unknown system setting: " + key);
        }
    }

    private static String intIn(String key, String v, int min, int max) {
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) throw new NumberFormatException();
            return String.valueOf(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number from " + min + " to " + max);
        }
    }
}
