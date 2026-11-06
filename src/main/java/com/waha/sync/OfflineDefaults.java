package com.waha.sync;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// Offline mode settings with the values used when no property row exists. GET /api/config always
// returns every one of them, so the kiosk never has to guess.
public final class OfflineDefaults {

    public static final Map<String, String> VALUES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("offline_mode", "false");
        m.put("sync_pull_start_delay_seconds", "60");
        m.put("sync_pull_interval_minutes", "10");
        m.put("sync_pull_patch_size", "200");
        m.put("sync_pull_timeout_seconds", "30");
        m.put("sync_idle_seconds", "10");
        m.put("sync_push_interval_seconds", "60");
        m.put("sync_push_timeout_seconds", "60");
        m.put("sync_push_retry_max_delay_minutes", "15");
        m.put("push_paid_orders_only", "true");
        m.put("scan_disabled_products", "false");
        VALUES = Collections.unmodifiableMap(m);
    }

    private OfflineDefaults() {}
}
