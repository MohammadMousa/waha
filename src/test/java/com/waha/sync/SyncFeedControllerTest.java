package com.waha.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncFeedControllerTest {

    private static SyncFeedRepository.Row row(long id, String at) {
        return new SyncFeedRepository.Row(Instant.parse(at), id, Map.of("id", id));
    }

    @Test
    void fullPageWithExtraRowIsNotLastAndCursorIsLastReturnedRow() {
        var rows = List.of(row(1, "2026-01-01T10:00:00Z"), row(2, "2026-01-01T10:00:00Z"), row(3, "2026-01-01T10:00:05Z"));
        Map<String, Object> body = SyncFeedController.page(rows, 2, null, 0);
        assertEquals(false, body.get("last"));
        assertEquals(2, ((List<?>) body.get("items")).size());
        @SuppressWarnings("unchecked") Map<String, Object> next = (Map<String, Object>) body.get("next");
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), next.get("since"));
        assertEquals(2L, next.get("afterId"));
    }

    @Test
    void shortPageIsLast() {
        var rows = List.of(row(1, "2026-01-01T10:00:00Z"));
        Map<String, Object> body = SyncFeedController.page(rows, 2, null, 0);
        assertEquals(true, body.get("last"));
        assertEquals(1, ((List<?>) body.get("items")).size());
    }

    @Test
    void emptyPageKeepsTheCursor() {
        Instant since = Instant.parse("2026-01-01T10:00:00Z");
        Map<String, Object> body = SyncFeedController.page(List.of(), 2, since, 7);
        assertEquals(true, body.get("last"));
        @SuppressWarnings("unchecked") Map<String, Object> next = (Map<String, Object>) body.get("next");
        assertEquals(since, next.get("since"));
        assertEquals(7L, next.get("afterId"));
    }
}
