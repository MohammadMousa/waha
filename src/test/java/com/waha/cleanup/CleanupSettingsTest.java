package com.waha.cleanup;

import com.waha.system.SystemPropertySpecs;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanupSettingsTest {

    @Test
    void everyTaskHasASettingWithSaneDefaults() {
        Set<String> keys = new HashSet<>();
        for (var t : CleanupService.TASKS) {
            assertTrue(keys.add(t.key()), "duplicate task " + t.key());
            assertTrue(SystemPropertySpecs.KNOWN.containsKey(t.settingKey()), t.settingKey() + " not a known setting");
            assertTrue(t.defaultDays() == 0 || t.defaultDays() >= t.minDays(), t.key() + " default below minimum");
            assertTrue(SystemPropertySpecs.KNOWN.get(t.settingKey()).description().length() <= 255, t.key() + " description too long for the settings table");
            assertTrue(t.minDays() >= 14, t.key() + " minimum below 14 days");
        }
    }

    @Test
    void zeroTurnsOffAndLowerThanMinimumIsRefused() {
        assertEquals("0", SystemPropertySpecs.clean("Cleanup_AuditLog_Days", "0"));
        assertEquals("14", SystemPropertySpecs.clean("Cleanup_AuditLog_Days", " 14 "));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Cleanup_AuditLog_Days", "7"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Cleanup_SyncRemovals_Days", "29"));
        assertEquals("30", SystemPropertySpecs.clean("Cleanup_SyncRemovals_Days", "30"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Cleanup_DeviceLogs_Days", "abc"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Cleanup_DeviceLogs_Days", "99999"));
    }

    @Test
    void protectedTablesAreNeverInTheList() {
        for (var t : CleanupService.TASKS) {
            for (String banned : new String[]{"orders", "order_items", "payments", "coupon_redemptions", "offer_applications"}) {
                assertTrue(!t.key().equals(banned), banned + " must never be cleaned");
            }
        }
    }

    @Test
    void unpaidOrdersCleanupIsOffByDefaultAndNeedsAtLeastFifteenDays() {
        var t = CleanupService.task("unpaid_orders");
        assertEquals(0, t.defaultDays());
        assertEquals(15, t.minDays());
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Cleanup_UnpaidOrders_Days", "14"));
        assertEquals("30", SystemPropertySpecs.clean("Cleanup_UnpaidOrders_Days", "30"));
        assertEquals("0", SystemPropertySpecs.clean("Cleanup_UnpaidOrders_Days", "0"));
    }
}
