package com.waha.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WasteRulesTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @Test
    void reasonsAreCheckedAgainstTheCentralList() {
        assertEquals("EXPIRED", WasteRules.reason(" expired "));
        assertThrows(IllegalArgumentException.class, () -> WasteRules.reason("stolen"));
        assertThrows(IllegalArgumentException.class, () -> WasteRules.reason(null));
        assertEquals(5, WasteRules.REASONS.size());
    }

    @Test
    void linesAreWholePositiveNumbersAndSameProductIsAddedUp() throws Exception {
        Map<Long, Integer> m = WasteRules.lines(M.readTree("[{\"productId\":5,\"quantity\":2},{\"productId\":7,\"quantity\":1},{\"productId\":5,\"quantity\":3}]"));
        assertEquals(Map.of(5L, 5, 7L, 1), m);
        for (String bad : new String[]{"[]", "{}", "[{\"productId\":5,\"quantity\":0}]", "[{\"productId\":5,\"quantity\":-1}]",
            "[{\"productId\":5,\"quantity\":2.5}]", "[{\"productId\":5,\"quantity\":\"3\"}]", "[{\"quantity\":3}]",
            "[{\"productId\":0,\"quantity\":3}]", "[{\"productId\":5,\"quantity\":1000001}]"})
            assertThrows(IllegalArgumentException.class, () -> WasteRules.lines(M.readTree(bad)), bad);
        assertThrows(IllegalArgumentException.class, () -> WasteRules.lines(M.readTree(
            "[{\"productId\":5,\"quantity\":600000},{\"productId\":5,\"quantity\":600000}]")));
    }

    @Test
    void tooManyLinesAreRefused() throws Exception {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= 501; i++) sb.append(i > 1 ? "," : "").append("{\"productId\":").append(i).append(",\"quantity\":1}");
        String json = sb.append("]").toString();
        assertThrows(IllegalArgumentException.class, () -> WasteRules.lines(M.readTree(json)));
    }

    @Test
    void dateDefaultsToNowAndIsNotInTheFuture() {
        assertEquals(NOW, WasteRules.when(null, NOW));
        assertEquals(NOW, WasteRules.when("  ", NOW));
        assertEquals(Instant.parse("2026-11-16T09:30:00Z"), WasteRules.when("2026-11-16T09:30:00Z", NOW));
        assertEquals(Instant.parse("2026-11-16T09:30:00Z"), WasteRules.when("2026-11-16T12:30:00+03:00", NOW));
        assertEquals(Instant.parse("2026-11-16T09:30:00Z"), WasteRules.when("2026-11-16T09:30", NOW));
        assertThrows(IllegalArgumentException.class, () -> WasteRules.when("2026-11-25T09:30:00Z", NOW));
        assertThrows(IllegalArgumentException.class, () -> WasteRules.when("1999-01-01T00:00:00Z", NOW));
        assertThrows(IllegalArgumentException.class, () -> WasteRules.when("yesterday", NOW));
    }

    @Test
    void notesReferenceAndClientRef() {
        assertNull(WasteRules.notes("  "));
        assertEquals("x", WasteRules.notes(" x "));
        assertThrows(IllegalArgumentException.class, () -> WasteRules.notes("x".repeat(1001)));
        assertNull(WasteRules.clientRef(null));
        assertEquals("a1-b_2", WasteRules.clientRef("a1-b_2"));
        assertThrows(IllegalArgumentException.class, () -> WasteRules.clientRef("bad value!"));
    }
}
