package com.waha.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineDefaultsTest {

    @Test
    void everyOfflinePropertyHasADefaultAndOfflineIsOffByDefault() {
        assertEquals(15, OfflineDefaults.VALUES.size());
        assertEquals("180", OfflineDefaults.VALUES.get("sync_stagger_seconds"));
        assertEquals("false", OfflineDefaults.VALUES.get("scan_disabled_products"));
        assertEquals("false", OfflineDefaults.VALUES.get("offline_unpaid_cleanup_enabled"));
        assertEquals("15", OfflineDefaults.VALUES.get("offline_unpaid_cleanup_days_offset"));
        assertEquals("false", OfflineDefaults.VALUES.get("offline_audit_cleanup"));
        assertEquals("false", OfflineDefaults.VALUES.get("offline_mode"));
        assertEquals("true", OfflineDefaults.VALUES.get("push_paid_orders_only"));
        assertEquals("60", OfflineDefaults.VALUES.get("sync_pull_interval_minutes"));
        assertEquals("3", OfflineDefaults.VALUES.get("sync_push_interval_minutes"));
        assertTrue(OfflineDefaults.VALUES.keySet().stream().allMatch(k -> k.equals("offline_mode")
            || k.equals("push_paid_orders_only") || k.equals("scan_disabled_products") || k.startsWith("offline_") || k.startsWith("sync_")));
    }
}
