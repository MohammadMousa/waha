package com.waha.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SyncPullServiceTest {

    static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

    // Rows 1..n per table, update time = BASE + id seconds; same keyset rule as the real queries.
    static class FakeFeeds implements SyncFeedSource {
        final Map<SyncTable, Integer> counts = new EnumMap<>(SyncTable.class);
        final List<SyncTable> asked = new ArrayList<>();
        public List<SyncFeedRepository.Row> fetch(SyncTable t, Instant since, long afterId, int limit) {
            asked.add(t);
            List<SyncFeedRepository.Row> out = new ArrayList<>();
            for (long id = 1; id <= counts.getOrDefault(t, 0) && out.size() < limit; id++) {
                Instant at = BASE.plusSeconds(id);
                boolean after = since == null || at.isAfter(since) || (at.equals(since) && id > afterId);
                if (after) out.add(new SyncFeedRepository.Row(at, id, new LinkedHashMap<>(Map.of("id", id))));
            }
            return out;
        }
    }

    static class FakeRemovals implements SyncPullService.Removals {
        long latest = 10, purged = 0;
        final List<Long> ids = new ArrayList<>();
        public long latestId() { return latest; }
        public long purgedUpTo() { return purged; }
        public List<SyncRemovalRepository.Removal> after(long afterId, int limit) {
            return ids.stream().filter(i -> i > afterId).limit(limit)
                .map(i -> new SyncRemovalRepository.Removal(i, new LinkedHashMap<>(Map.of("id", i)))).toList();
        }
    }

    static Map<SyncTable, SyncTableSetting> defaults() {
        Map<SyncTable, SyncTableSetting> m = new EnumMap<>(SyncTable.class);
        for (SyncTable t : SyncTable.values()) m.put(t, SyncTableSetting.DEFAULT);
        return m;
    }

    static Map<String, SyncPullService.Cursor> allFromStart() {
        Map<String, SyncPullService.Cursor> m = new LinkedHashMap<>();
        for (SyncTable t : SyncTable.FEEDS) m.put(t.key(), null);
        return m;
    }

    static Map<String, SyncPullService.Cursor> fromStart(String name) {
        Map<String, SyncPullService.Cursor> m = new LinkedHashMap<>();
        m.put(name, null);
        return m;
    }

    static Map<String, SyncPullService.Cursor> allAt(Instant since, long afterId, Instant checkedAt) {
        Map<String, SyncPullService.Cursor> m = new LinkedHashMap<>();
        for (SyncTable t : SyncTable.FEEDS) m.put(t.key(), new SyncPullService.Cursor(since, afterId, checkedAt));
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> tables(Map<String, Object> r) { return (Map<String, Object>) r.get("tables"); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> table(Map<String, Object> r, String name) { return (Map<String, Object>) tables(r).get(name); }
    static int items(Map<String, Object> t) { return ((List<?>) t.get("items")).size(); }

    @Test
    void nothingChangedIsOneSmallAnswerWithEveryTableLast() {
        var feeds = new FakeFeeds();
        var req = new SyncPullService.Request(null, false, 10L, null, allAt(NOW, 0, null));
        var r = SyncPullService.pull(req, 200, defaults(), NOW, feeds, new FakeRemovals());
        assertEquals(6, tables(r).size());
        for (SyncTable t : SyncTable.FEEDS) {
            assertEquals(true, table(r, t.key()).get("last"), t.key());
            assertEquals(0, items(table(r, t.key())));
        }
        @SuppressWarnings("unchecked") var rem = (Map<String, Object>) r.get("removals");
        assertEquals(true, rem.get("last"));
        assertEquals(false, rem.get("resetRequired"));
        assertEquals(7, ((Map<?, ?>) r.get("tableConfig")).size());
    }

    @Test
    void firstSyncRespectsTheRowBudgetAndDefersTheRest() {
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.PRODUCTS, 400);
        feeds.counts.put(SyncTable.BARCODES, 400);
        feeds.counts.put(SyncTable.CATEGORIES, 5);
        var req = new SyncPullService.Request(300, false, null, null, allFromStart());
        var r = SyncPullService.pull(req, 200, defaults(), NOW, feeds, new FakeRemovals());
        assertEquals(300, items(table(r, "products")));
        assertEquals(false, table(r, "products").get("last"));
        assertEquals(200, items(table(r, "barcodes")));             // 500 - 300 left
        assertEquals(false, table(r, "barcodes").get("last"));
        var cat = table(r, "categories");                           // no room left
        assertEquals(true, cat.get("deferred"));
        assertEquals(false, cat.get("last"));
        assertEquals(0, items(cat));
        @SuppressWarnings("unchecked") var next = (Map<String, Object>) cat.get("next");
        assertNull(next.get("since"));
        assertEquals(0L, next.get("afterId"));
        assertFalse(feeds.asked.contains(SyncTable.CATEGORIES));   // a deferred table is not even queried
        int total = 0;
        for (var t : tables(r).values()) { @SuppressWarnings("unchecked") var m = (Map<String, Object>) t; if (m.get("items") != null) total += items(m); }
        assertTrue(total <= SyncPullService.MAX_ROWS);
    }

    @Test
    void loopingFromTheStartGivesTheSameRowsAsThePerTableFeeds() {
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.PRODUCTS, 1100);
        feeds.counts.put(SyncTable.BARCODES, 700);
        Map<String, SyncPullService.Cursor> cursors = allFromStart();
        Set<Long> products = new HashSet<>(), barcodes = new HashSet<>();
        int calls = 0;
        boolean more = true;
        while (more && calls < 20) {
            calls++;
            var r = SyncPullService.pull(new SyncPullService.Request(500, false, null, null, cursors), 200, defaults(), NOW, feeds, new FakeRemovals());
            more = false;
            for (SyncTable t : SyncTable.FEEDS) {
                var tb = table(r, t.key());
                @SuppressWarnings("unchecked") var list = (List<Map<String, Object>>) tb.get("items");
                for (var row : list) { if (t == SyncTable.PRODUCTS) products.add((Long) row.get("id")); if (t == SyncTable.BARCODES) barcodes.add((Long) row.get("id")); }
                @SuppressWarnings("unchecked") var next = (Map<String, Object>) tb.get("next");
                cursors.put(t.key(), new SyncPullService.Cursor((Instant) next.get("since"), (Long) next.get("afterId"), null));
                if (Boolean.FALSE.equals(tb.get("last"))) more = true;
            }
        }
        assertEquals(1100, products.size());
        assertEquals(700, barcodes.size());
        assertFalse(more);
        assertTrue(calls >= 4 && calls < 20, "calls=" + calls);
    }

    @Test
    void aTableLeftOutIsNotPulledAndAnUnknownNameIsSkipped() {
        var feeds = new FakeFeeds();
        Map<String, SyncPullService.Cursor> wanted = new LinkedHashMap<>();
        wanted.put("products", null);
        wanted.put("orders-from-the-future", null);
        wanted.put("removals", null);   // not a table with a feed
        var r = SyncPullService.pull(new SyncPullService.Request(null, false, null, null, wanted), 200, defaults(), NOW, feeds, new FakeRemovals());
        assertEquals(Set.of("products", "orders-from-the-future", "removals"), tables(r).keySet());
        assertEquals("unknown", table(r, "orders-from-the-future").get("skipped"));
        assertEquals("unknown", table(r, "removals").get("skipped"));
        assertEquals(List.of(SyncTable.PRODUCTS), feeds.asked);
    }

    @Test
    void aSwitchedOffTableIsSkippedWithoutAQueryEvenWhenForced() {
        var cfg = defaults();
        cfg.put(SyncTable.PRODUCT_IMAGES, new SyncTableSetting(false, null, null));
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.PRODUCT_IMAGES, 50);
        var r = SyncPullService.pull(new SyncPullService.Request(null, true, null, null, allFromStart()), 200, cfg, NOW, feeds, new FakeRemovals());
        assertEquals("disabled", table(r, "product-images").get("skipped"));
        assertFalse(feeds.asked.contains(SyncTable.PRODUCT_IMAGES));
        @SuppressWarnings("unchecked") var conf = (Map<String, Object>) ((Map<String, Object>) r.get("tableConfig")).get("product-images");
        assertEquals(false, conf.get("active"));
    }

    @Test
    void anExpirationPeriodSkipsAFreshTableAndPullsAnExpiredOne() {
        var cfg = defaults();
        cfg.put(SyncTable.CATEGORIES, new SyncTableSetting(true, "1D", null));
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.CATEGORIES, 3);

        // checked 2 hours ago: fresh
        var fresh = new SyncPullService.Request(null, false, null, null,
            Map.of("categories", new SyncPullService.Cursor(null, 0, NOW.minusSeconds(7200))));
        var r = SyncPullService.pull(fresh, 200, cfg, NOW, feeds, new FakeRemovals());
        assertEquals("fresh", table(r, "categories").get("skipped"));
        assertTrue(feeds.asked.isEmpty());

        // checked 25 hours ago: expired
        var old = new SyncPullService.Request(null, false, null, null,
            Map.of("categories", new SyncPullService.Cursor(null, 0, NOW.minusSeconds(25 * 3600))));
        r = SyncPullService.pull(old, 200, cfg, NOW, feeds, new FakeRemovals());
        assertEquals(3, items(table(r, "categories")));

        // exactly at the limit counts as expired
        var edge = new SyncPullService.Request(null, false, null, null,
            Map.of("categories", new SyncPullService.Cursor(null, 0, NOW.minusSeconds(86400))));
        r = SyncPullService.pull(edge, 200, cfg, NOW, feeds, new FakeRemovals());
        assertEquals(3, items(table(r, "categories")));
    }

    @Test
    void noCheckedAtANullCursorForceOrAFutureCheckedAtAlwaysPull() {
        var cfg = defaults();
        cfg.put(SyncTable.CATEGORIES, new SyncTableSetting(true, "1D", null));
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.CATEGORIES, 2);
        for (var c : new SyncPullService.Cursor[]{null, new SyncPullService.Cursor(null, 0, null), new SyncPullService.Cursor(null, 0, NOW.plusSeconds(3600))}) {
            Map<String, SyncPullService.Cursor> m = new LinkedHashMap<>();
            m.put("categories", c);
            var r = SyncPullService.pull(new SyncPullService.Request(null, false, null, null, m), 200, cfg, NOW, feeds, new FakeRemovals());
            assertEquals(2, items(table(r, "categories")));
        }
        var forced = SyncPullService.pull(new SyncPullService.Request(null, true, null, null,
            Map.of("categories", new SyncPullService.Cursor(null, 0, NOW.minusSeconds(60)))), 200, cfg, NOW, feeds, new FakeRemovals());
        assertEquals(2, items(table(forced, "categories")));
    }

    @Test
    void aFreshSkipIsNotAnItemsAnswerAndSpendsNoBudget() {
        var cfg = defaults();
        cfg.put(SyncTable.PRODUCTS, new SyncTableSetting(true, "3H", null));
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.PRODUCTS, 900);
        feeds.counts.put(SyncTable.BARCODES, 900);
        Map<String, SyncPullService.Cursor> m = new LinkedHashMap<>();
        m.put("products", new SyncPullService.Cursor(null, 0, NOW.minusSeconds(60)));
        m.put("barcodes", null);
        var r = SyncPullService.pull(new SyncPullService.Request(500, false, null, null, m), 200, cfg, NOW, feeds, new FakeRemovals());
        assertNull(table(r, "products").get("items"));
        assertEquals(500, items(table(r, "barcodes")));
    }

    // ---- removals

    @SuppressWarnings("unchecked")
    static Map<String, Object> rem(Map<String, Object> r) { return (Map<String, Object>) r.get("removals"); }

    @Test
    void removalsWithoutAPositionGiveTheStartingPositionOnly() {
        var removals = new FakeRemovals();
        removals.ids.addAll(List.of(3L, 4L));
        var r = SyncPullService.pull(new SyncPullService.Request(null, false, null, null, Map.of()), 200, defaults(), NOW, new FakeFeeds(), removals);
        assertEquals(List.of(), rem(r).get("items"));
        assertEquals(Map.of("afterId", 10L), rem(r).get("next"));
        assertEquals(false, rem(r).get("resetRequired"));
    }

    @Test
    void removalsListWhatCameAfterThePosition() {
        var removals = new FakeRemovals();
        removals.ids.addAll(List.of(3L, 4L, 5L));
        removals.latest = 5;
        var r = SyncPullService.pull(new SyncPullService.Request(null, false, 3L, null, Map.of()), 200, defaults(), NOW, new FakeFeeds(), removals);
        assertEquals(2, ((List<?>) rem(r).get("items")).size());
        assertEquals(true, rem(r).get("last"));
        assertEquals(Map.of("afterId", 5L), rem(r).get("next"));
    }

    @Test
    void aPositionOlderThanTheCleanedPartRequiresAReset() {
        var removals = new FakeRemovals();
        removals.purged = 8;
        var r = SyncPullService.pull(new SyncPullService.Request(null, false, 5L, null, Map.of()), 200, defaults(), NOW, new FakeFeeds(), removals);
        assertEquals(true, rem(r).get("resetRequired"));
        assertEquals(List.of(), rem(r).get("items"));
        assertEquals(Map.of("afterId", 10L), rem(r).get("next"));
    }

    @Test
    void switchedOffRemovalsStillHandOutThePositionAndListNothing() {
        var cfg = defaults();
        cfg.put(SyncTable.REMOVALS, new SyncTableSetting(false, null, null));
        var removals = new FakeRemovals();
        removals.ids.addAll(List.of(11L, 12L));
        removals.latest = 12;
        for (Long after : new Long[]{null, 3L}) {
            var r = SyncPullService.pull(new SyncPullService.Request(null, true, after, null, Map.of()), 200, cfg, NOW, new FakeFeeds(), removals);
            assertEquals("disabled", rem(r).get("skipped"));
            assertEquals(Map.of("afterId", 12L), rem(r).get("next"));
            assertNull(rem(r).get("items"));
        }
    }

    @Test
    void removalsExpirationSkipsOnlyADeviceThatAlreadyHasAPosition() {
        var cfg = defaults();
        cfg.put(SyncTable.REMOVALS, new SyncTableSetting(true, "1D", null));
        var removals = new FakeRemovals();
        var withPosition = SyncPullService.pull(new SyncPullService.Request(null, false, 10L, NOW.minusSeconds(60), Map.of()), 200, cfg, NOW, new FakeFeeds(), removals);
        assertEquals(Map.of("skipped", "fresh"), rem(withPosition));
        var without = SyncPullService.pull(new SyncPullService.Request(null, false, null, NOW.minusSeconds(60), Map.of()), 200, cfg, NOW, new FakeFeeds(), removals);
        assertEquals(Map.of("afterId", 10L), rem(without).get("next"));
        var forced = SyncPullService.pull(new SyncPullService.Request(null, true, 10L, NOW.minusSeconds(60), Map.of()), 200, cfg, NOW, new FakeFeeds(), removals);
        assertEquals(true, rem(forced).get("last"));
    }

    @Test
    void removalsCountAgainstTheRowBudget() {
        var removals = new FakeRemovals();
        for (long i = 1; i <= 600; i++) removals.ids.add(i);
        removals.latest = 600;
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.PRODUCTS, 10);
        var r = SyncPullService.pull(new SyncPullService.Request(500, false, 0L, null, fromStart("products")), 200, defaults(), NOW, feeds, removals);
        assertEquals(500, ((List<?>) rem(r).get("items")).size());
        assertEquals(false, rem(r).get("last"));
        assertEquals(true, table(r, "products").get("deferred"));
    }

    @Test
    void sizeIsClampedAndTheDefaultIsUsedWhenMissing() {
        var feeds = new FakeFeeds();
        feeds.counts.put(SyncTable.PRODUCTS, 300);
        var r = SyncPullService.pull(new SyncPullService.Request(null, false, null, null, fromStart("products")), 50, defaults(), NOW, feeds, new FakeRemovals());
        assertEquals(50, items(table(r, "products")));
        r = SyncPullService.pull(new SyncPullService.Request(5000, false, null, null, fromStart("products")), 50, defaults(), NOW, feeds, new FakeRemovals());
        assertEquals(300, items(table(r, "products")));
    }
}
