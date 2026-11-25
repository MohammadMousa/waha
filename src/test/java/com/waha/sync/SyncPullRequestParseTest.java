package com.waha.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SyncPullRequestParseTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static SyncPullService.Request parse(String json) throws Exception {
        return SyncPullController.parse(M.readTree(json));
    }

    @Test
    void readsTheDocumentedRequest() throws Exception {
        var r = parse("""
            {"size":200,"force":true,"removalsAfterId":118,"removalsCheckedAt":"2026-11-25T08:00:00Z",
             "tables":{"products":{"since":"2026-11-24T10:00:00Z","afterId":1650,"checkedAt":"2026-11-25T08:00:00Z"},
                       "categories":null,"payment-methods":{}}}""");
        assertEquals(200, r.size());
        assertTrue(r.force());
        assertEquals(118L, r.removalsAfterId());
        assertEquals(Instant.parse("2026-11-25T08:00:00Z"), r.removalsCheckedAt());
        var p = r.tables().get("products");
        assertEquals(1650, p.afterId());
        assertEquals(Instant.parse("2026-11-24T10:00:00Z"), p.since());
        assertEquals(Instant.parse("2026-11-25T08:00:00Z"), p.checkedAt());
        assertTrue(r.tables().containsKey("categories") && r.tables().get("categories") == null);
        assertTrue(r.tables().containsKey("payment-methods") && r.tables().get("payment-methods") == null);
    }

    @Test
    void anEmptyOrMissingBodyAsksForNothing() throws Exception {
        for (var r : new SyncPullService.Request[]{SyncPullController.parse(null), parse("{}"), parse("{\"tables\":null}")}) {
            assertNull(r.size());
            assertFalse(r.force());
            assertNull(r.removalsAfterId());
            assertTrue(r.tables().isEmpty());
        }
    }

    @Test
    void aTableWithOnlySinceStartsAfterIdAtZero() throws Exception {
        var r = parse("{\"tables\":{\"products\":{\"since\":\"2026-11-24T10:00:00Z\"}}}");
        assertEquals(0, r.tables().get("products").afterId());
        assertNull(r.tables().get("products").checkedAt());
    }

    @Test
    void badInputIsAPlainError() {
        for (String bad : new String[]{
            "[]", "{\"size\":0}", "{\"size\":\"x\"}", "{\"size\":1.5}", "{\"force\":\"yes\"}", "{\"removalsAfterId\":-1}",
            "{\"removalsAfterId\":\"1\"}", "{\"removalsCheckedAt\":\"yesterday\"}", "{\"tables\":[]}", "{\"tables\":{\"products\":5}}",
            "{\"tables\":{\"products\":{\"since\":\"nope\"}}}", "{\"tables\":{\"products\":{\"afterId\":-3}}}",
            "{\"tables\":{\"products\":{\"checkedAt\":\"nope\"}}}"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(bad), bad);
        }
    }
}
