package com.waha.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncTableConfigServiceTest {

    // Rows per organization, and how many times they were read.
    static class FakeStore implements SyncTableConfigService.Store {
        final Map<Long, Map<SyncTable, SyncTableSetting>> rows = new HashMap<>();
        int loads;
        public Map<SyncTable, SyncTableSetting> load(long org) { loads++; return new HashMap<>(rows.getOrDefault(org, Map.of())); }
        public void save(long org, SyncTable t, SyncTableSetting s) { rows.computeIfAbsent(org, k -> new HashMap<>()).put(t, s); }
    }

    private Instant now = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void noRowsMeansEverythingOnWithNoPeriod() {
        var svc = new SyncTableConfigService(new FakeStore(), () -> now);
        var all = svc.forOrganization(1);
        assertEquals(7, all.size());
        for (SyncTable t : SyncTable.values()) {
            assertTrue(all.get(t).active());
            assertNull(all.get(t).expirationPeriod());
            assertEquals(0, all.get(t).ttlSeconds(t));
        }
    }

    @Test
    void readsAreCachedForAMinuteAndAnUpdateShowsAtOnce() {
        var store = new FakeStore();
        var svc = new SyncTableConfigService(store, () -> now);
        svc.forOrganization(1);
        svc.forOrganization(1);
        assertEquals(1, store.loads);
        now = now.plusSeconds(59);
        svc.forOrganization(1);
        assertEquals(1, store.loads);
        now = now.plusSeconds(2);
        svc.forOrganization(1);
        assertEquals(2, store.loads);

        // an admin change is visible right away on this server
        svc.update(1, SyncTable.CATEGORIES, false, false, null, false, null);
        assertFalse(svc.get(1, SyncTable.CATEGORIES).active());
    }

    @Test
    void organizationsDoNotShareSettings() {
        var svc = new SyncTableConfigService(new FakeStore(), () -> now);
        svc.update(1, SyncTable.PRODUCT_IMAGES, false, true, "1D", false, null);
        assertFalse(svc.get(1, SyncTable.PRODUCT_IMAGES).active());
        assertTrue(svc.get(2, SyncTable.PRODUCT_IMAGES).active());
        assertNull(svc.get(2, SyncTable.PRODUCT_IMAGES).expirationPeriod());
    }

    @Test
    void updateKeepsWhatIsNotGivenAndNullClearsAPeriod() {
        var svc = new SyncTableConfigService(new FakeStore(), () -> now);
        svc.update(1, SyncTable.PRODUCTS, null, true, "3h", true, "  catalog  ");
        var s = svc.get(1, SyncTable.PRODUCTS);
        assertTrue(s.active());
        assertEquals("3H", s.expirationPeriod());
        assertEquals("catalog", s.description());
        assertEquals(10800, s.ttlSeconds(SyncTable.PRODUCTS));

        svc.update(1, SyncTable.PRODUCTS, false, false, null, false, null); // only the switch
        s = svc.get(1, SyncTable.PRODUCTS);
        assertFalse(s.active());
        assertEquals("3H", s.expirationPeriod());
        assertEquals("catalog", s.description());

        svc.update(1, SyncTable.PRODUCTS, null, true, null, true, "");       // clear both
        s = svc.get(1, SyncTable.PRODUCTS);
        assertNull(s.expirationPeriod());
        assertNull(s.description());
    }

    @Test
    void badValuesAreRefusedAndNothingIsSaved() {
        var store = new FakeStore();
        var svc = new SyncTableConfigService(store, () -> now);
        assertThrows(IllegalArgumentException.class, () -> svc.update(1, SyncTable.REMOVALS, null, true, "8D", false, null));
        assertThrows(IllegalArgumentException.class, () -> svc.update(1, SyncTable.CATEGORIES, null, true, "soon", false, null));
        assertThrows(IllegalArgumentException.class, () -> svc.update(1, SyncTable.CATEGORIES, null, false, null, true, "x".repeat(256)));
        assertTrue(store.rows.isEmpty());
    }
}
