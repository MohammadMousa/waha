package com.waha.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Pure helpers for the session list: the login info stored with a session (OS, model, browser)
// and the choice of which old sessions go when an account is over its cap.
final class SessionInfo {

    private SessionInfo() {}

    private static final Set<String> KEYS = Set.of("os", "osVersion", "model", "browser");
    private static final int MAX_VALUE_LENGTH = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    // Only the known keys are kept, each trimmed and shortened; nothing else a client sends is stored.
    // For a browser login the browser and OS are read from the user agent when the client did not send them.
    // Returns JSON text, or null when there is nothing to store.
    static String toJson(Object sessionProperties, String userAgent) {
        Map<String, String> info = new LinkedHashMap<>();
        if (sessionProperties instanceof Map<?, ?> raw) {
            for (String key : KEYS) {
                Object v = raw.get(key);
                String clean = clean(v);
                if (clean != null) info.put(key, clean);
            }
        }
        if (userAgent != null && userAgent.contains("Mozilla/")) {
            String browser = browserOf(userAgent);
            String os = osOf(userAgent);
            if (browser != null) info.putIfAbsent("browser", browser);
            if (os != null) info.putIfAbsent("os", os);
        }
        if (info.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(info);
        } catch (Exception e) {
            return null;
        }
    }

    // The app version is optional: trimmed and cut at 50 characters; blank means not sent.
    static String appVersion(Object raw) {
        if (raw == null) return null;
        String v = raw.toString().trim();
        return v.isEmpty() ? null : (v.length() > 50 ? v.substring(0, 50) : v);
    }

    private static String clean(Object v) {
        if (v == null) return null;
        String s = v.toString().replaceAll("\\p{Cntrl}", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > MAX_VALUE_LENGTH ? s.substring(0, MAX_VALUE_LENGTH) : s;
    }

    static String browserOf(String ua) {
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("Firefox/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return null;
    }

    static String osOf(String ua) {
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
        if (ua.contains("Linux") || ua.contains("X11")) return "Linux";
        return null;
    }

    // A session of an account, as the cap sees it: when it was created and when it was last seen.
    record Candidate(String token, Instant createdAt, Instant lastSeenAt) {
        Instant activity() {
            if (lastSeenAt == null) return createdAt;
            return createdAt != null && createdAt.isAfter(lastSeenAt) ? createdAt : lastSeenAt;
        }
    }

    // The tokens to end so that at most cap sessions remain: the oldest activity goes first,
    // and the session just created (keepToken) is never chosen. A cap of 0 or less means off.
    static List<String> overCap(List<Candidate> sessions, int cap, String keepToken) {
        List<String> out = new ArrayList<>();
        if (cap <= 0 || sessions.size() <= cap) return out;
        List<Candidate> removable = new ArrayList<>(sessions.stream()
            .filter(c -> !c.token().equals(keepToken)).toList());
        removable.sort(Comparator.comparing(Candidate::activity, Comparator.nullsFirst(Comparator.naturalOrder())));
        int excess = sessions.size() - cap;
        for (int i = 0; i < excess && i < removable.size(); i++) out.add(removable.get(i).token());
        return out;
    }
}
