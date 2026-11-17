package com.waha.inventory;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// Pure rules for recording a waste. Every method returns the cleaned value or throws IllegalArgumentException
// with a message the client can show.
final class WasteRules {

    private WasteRules() {}

    record Reason(String code, String nameEn, String nameAr) {}

    // The one central list of reasons; the apps read it from the server.
    static final List<Reason> REASONS = List.of(
        new Reason("EXPIRED", "Expired", "منتهي الصلاحية"),
        new Reason("DAMAGED", "Damaged", "تالف"),
        new Reason("SPOILED", "Spoiled", "فاسد"),
        new Reason("MISSING", "Missing", "مفقود"),
        new Reason("OTHER", "Other", "أخرى"));

    static final int MAX_LINES = 500;
    static final int MAX_QUANTITY = 1_000_000;
    private static final Pattern CLIENT_REF = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    static String reason(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase();
        for (Reason r : REASONS) if (r.code().equals(v)) return v;
        throw new IllegalArgumentException("reason must be one of " + REASONS.stream().map(Reason::code).toList());
    }

    // product id -> whole quantity. The same product on several lines is added up.
    static Map<Long, Integer> lines(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) throw new IllegalArgumentException("At least one item is required");
        if (items.size() > MAX_LINES) throw new IllegalArgumentException("At most " + MAX_LINES + " items per waste");
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (JsonNode n : items) {
            JsonNode p = n.get("productId");
            JsonNode q = n.get("quantity");
            if (p == null || !p.isIntegralNumber() || p.asLong() <= 0) throw new IllegalArgumentException("Every item needs a productId");
            if (q == null || !q.isIntegralNumber()) throw new IllegalArgumentException("Quantity must be a whole number");
            long qty = q.asLong();
            if (qty <= 0) throw new IllegalArgumentException("Quantity must be positive");
            long total = out.getOrDefault(p.asLong(), 0) + qty;
            if (total > MAX_QUANTITY) throw new IllegalArgumentException("Quantity is too large");
            out.put(p.asLong(), (int) total);
        }
        return out;
    }

    // Empty means now. A time with no offset (2026-11-17T09:30) is read as UTC. Not in the future, not before 2000.
    static Instant when(String raw, Instant now) {
        if (raw == null || raw.isBlank()) return now;
        Instant t;
        String v = raw.trim();
        try {
            try {
                t = OffsetDateTime.parse(v).toInstant();
            } catch (DateTimeParseException e) {
                t = LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must look like 2026-11-17T09:30:00Z");
        }
        if (t.isAfter(now.plusSeconds(24 * 3600L))) throw new IllegalArgumentException("date cannot be in the future");
        if (t.isBefore(Instant.parse("2000-01-01T00:00:00Z"))) throw new IllegalArgumentException("date is not valid");
        return t;
    }

    static String notes(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() > 1000) throw new IllegalArgumentException("notes are too long (1000 characters at most)");
        return v;
    }

    static String clientRef(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (!CLIENT_REF.matcher(v).matches()) throw new IllegalArgumentException("clientRef must be up to 64 letters, digits, - or _");
        return v;
    }
}
