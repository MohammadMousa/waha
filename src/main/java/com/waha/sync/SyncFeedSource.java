package com.waha.sync;

import java.time.Instant;
import java.util.List;

// Reads one page (limit rows, ordered by update time then id) of a sync table for one organization and store.
@FunctionalInterface
public interface SyncFeedSource {
    List<SyncFeedRepository.Row> fetch(SyncTable table, Instant since, long afterId, int limit);
}
