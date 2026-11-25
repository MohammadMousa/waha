package com.waha.sync;

// One table's settings for one organization. ttlSeconds 0 = no expiration period.
public record SyncTableSetting(boolean active, String expirationPeriod, String description) {

    public static final SyncTableSetting DEFAULT = new SyncTableSetting(true, null, null);

    public long ttlSeconds(SyncTable table) {
        return expirationPeriod == null ? 0 : ExpirationPeriod.seconds(table, expirationPeriod);
    }
}
