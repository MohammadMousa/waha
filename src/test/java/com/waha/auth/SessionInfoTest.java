package com.waha.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionInfoTest {

    private static SessionInfo.Candidate c(String token, String created, String seen) {
        return new SessionInfo.Candidate(token, Instant.parse(created), seen == null ? null : Instant.parse(seen));
    }

    @Test
    void keepsOnlyKnownKeysAndShortensValues() {
        String json = SessionInfo.toJson(Map.of("os", "Android", "model", "x".repeat(300), "mode", "KIOSK", "evil", "1"), null);
        assertTrue(json.contains("\"os\":\"Android\""));
        assertFalse(json.contains("\"mode\""));
        assertFalse(json.contains("evil"));
        assertTrue(json.length() < 200);
    }

    @Test
    void nothingToStoreGivesNull() {
        assertNull(SessionInfo.toJson(null, null));
        assertNull(SessionInfo.toJson(Map.of("mode", "KIOSK"), "Dart/3.4 (dart:io)"));
    }

    @Test
    void browserLoginIsReadFromUserAgent() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0";
        String json = SessionInfo.toJson(null, ua);
        assertTrue(json.contains("\"browser\":\"Edge\""));
        assertTrue(json.contains("\"os\":\"Windows\""));
    }

    @Test
    void clientValuesWinOverUserAgent() {
        String json = SessionInfo.toJson(Map.of("os", "Android"), "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0");
        assertTrue(json.contains("\"os\":\"Android\""));
        assertTrue(json.contains("\"browser\":\"Chrome\""));
    }

    @Test
    void capEndsTheOldestActivityFirst() {
        var sessions = List.of(
            c("a", "2026-10-01T00:00:00Z", null),
            c("b", "2026-10-02T00:00:00Z", "2026-10-28T00:00:00Z"),
            c("c", "2026-10-03T00:00:00Z", null),
            c("new", "2026-10-29T00:00:00Z", null));
        assertEquals(List.of("a"), SessionInfo.overCap(sessions, 3, "new"));
        assertEquals(List.of("a", "c"), SessionInfo.overCap(sessions, 2, "new"));
    }

    @Test
    void capNeverEndsTheNewSessionAndZeroMeansOff() {
        var sessions = List.of(c("a", "2026-10-01T00:00:00Z", null), c("new", "2026-09-01T00:00:00Z", null));
        assertEquals(List.of("a"), SessionInfo.overCap(sessions, 1, "new"));
        assertTrue(SessionInfo.overCap(sessions, 0, "new").isEmpty());
        assertTrue(SessionInfo.overCap(sessions, 5, "new").isEmpty());
    }

    @Test
    void appVersionIsTrimmedCutAndOptional() {
        assertNull(SessionInfo.appVersion(null));
        assertNull(SessionInfo.appVersion("   "));
        assertEquals("1.0.1", SessionInfo.appVersion(" 1.0.1 "));
        assertEquals(50, SessionInfo.appVersion("9".repeat(80)).length());
    }
}
