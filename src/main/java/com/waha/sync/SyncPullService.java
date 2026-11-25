package com.waha.sync;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The combined pull (docs/sync-combined-pull.md): removals and every requested table in one answer, with a row budget,
// table switches and expiration periods. Pure logic: data comes in through SyncFeedSource and Removals.
public final class SyncPullService {

    public static final int MAX_ROWS = 500;
    public static final int MAX_SIZE = 500;

    // A device's saved position in one table. checkedAt: when it last finished checking the table (server time), or null.
    public record Cursor(Instant since, long afterId, Instant checkedAt) {}

    // tables: the tables wanted; a null value starts that table from the beginning. A table left out is not pulled.
    public record Request(Integer size, boolean force, Long removalsAfterId, Instant removalsCheckedAt, Map<String, Cursor> tables) {}

    // Removal records of one organization.
    public interface Removals {
        long latestId();
        long purgedUpTo();
        List<SyncRemovalRepository.Removal> after(long afterId, int limit);
    }

    private SyncPullService() {}

    // Returns tableConfig, removals and tables (the caller adds serverTime and deviceConfig in front).
    public static Map<String, Object> pull(Request req, int defaultSize, Map<SyncTable, SyncTableSetting> config, Instant now,
                                           SyncFeedSource feeds, Removals removals) {
        int size = Math.max(1, Math.min(req.size() != null ? req.size() : defaultSize, MAX_SIZE));
        int budget = MAX_ROWS;
        // The newest removal id is read before any table, so a position never comes later than the rows beside it.
        long latest = removals.latestId();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tableConfig", tableConfig(config));

        // Removals
        SyncTableSetting rs = config.get(SyncTable.REMOVALS);
        Map<String, Object> rem = new LinkedHashMap<>();
        Long afterId = req.removalsAfterId();
        if (!rs.active()) {
            rem.put("skipped", "disabled");
            rem.put("next", Map.of("afterId", latest));
        } else if (afterId != null && fresh(rs.ttlSeconds(SyncTable.REMOVALS), req.force(), req.removalsCheckedAt(), now)) {
            rem.put("skipped", "fresh");
        } else {
            boolean reset = afterId != null && afterId < removals.purgedUpTo();
            List<Map<String, Object>> items = List.of();
            boolean last = true;
            long next = latest;
            if (afterId != null && !reset) {
                int limit = Math.max(1, Math.min(size, budget));
                var rows = removals.after(afterId, limit + 1);
                last = rows.size() <= limit;
                var page = last ? rows : rows.subList(0, limit);
                items = page.stream().map(SyncRemovalRepository.Removal::fields).toList();
                next = page.isEmpty() ? afterId : page.get(page.size() - 1).id();
                budget -= items.size();
            }
            rem.put("items", items);
            rem.put("last", last);
            rem.put("next", Map.of("afterId", next));
            rem.put("resetRequired", reset);
        }
        out.put("removals", rem);

        // Tables, in the fixed order
        Map<String, Object> tables = new LinkedHashMap<>();
        for (SyncTable t : SyncTable.FEEDS) {
            if (!req.tables().containsKey(t.key())) continue;
            Cursor c = req.tables().get(t.key());
            SyncTableSetting s = config.get(t);
            if (!s.active()) {
                tables.put(t.key(), Map.of("skipped", "disabled"));
                continue;
            }
            if (c != null && fresh(s.ttlSeconds(t), req.force(), c.checkedAt(), now)) {
                tables.put(t.key(), Map.of("skipped", "fresh"));
                continue;
            }
            Instant since = c == null ? null : c.since();
            long after = c == null ? 0 : c.afterId();
            if (budget <= 0) {
                Map<String, Object> next = new LinkedHashMap<>();
                next.put("since", since);
                next.put("afterId", after);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("items", List.of());
                d.put("last", false);
                d.put("deferred", true);
                d.put("next", next);
                tables.put(t.key(), d);
                continue;
            }
            int limit = Math.min(size, budget);
            Map<String, Object> body = SyncFeedController.page(feeds.fetch(t, since, after, limit + 1), limit, since, after);
            body.remove("serverTime");
            budget -= ((List<?>) body.get("items")).size();
            tables.put(t.key(), body);
        }
        // Names this server does not know (a newer kiosk): answered, never an error.
        for (String name : req.tables().keySet()) {
            if (SyncTable.feedByKey(name).isEmpty()) tables.put(name, Map.of("skipped", "unknown"));
        }
        out.put("tables", tables);
        return out;
    }

    // The table has an expiration period, this is not a forced pull, and the device checked it more recently than that.
    static boolean fresh(long ttlSeconds, boolean force, Instant checkedAt, Instant now) {
        return ttlSeconds > 0 && !force && checkedAt != null && !checkedAt.isAfter(now)
            && now.isBefore(checkedAt.plusSeconds(ttlSeconds));
    }

    static Map<String, Object> tableConfig(Map<SyncTable, SyncTableSetting> config) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (SyncTable t : SyncTable.values()) {
            SyncTableSetting s = config.get(t);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("active", s.active());
            e.put("ttlSeconds", s.ttlSeconds(t));
            m.put(t.key(), e);
        }
        return m;
    }
}
