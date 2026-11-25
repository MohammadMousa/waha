package com.waha.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncTableTest {

    @Test
    void feedNamesAreTheOnesTheSingleCallsUse() {
        assertEquals(List.of("products", "barcodes", "categories", "payment-methods", "store-payment-methods", "product-images"),
            SyncTable.FEEDS.stream().map(SyncTable::key).toList());
        assertTrue(SyncTable.feedByKey("removals").isEmpty());
        assertEquals(SyncTable.REMOVALS, SyncTable.byKey("removals").orElseThrow());
        assertTrue(SyncTable.byKey("orders").isEmpty());
    }

    @Test
    void sensitiveTablesAreTheFiveThatMatterForSelling() {
        assertEquals(List.of("products", "barcodes", "payment-methods", "store-payment-methods", "removals"),
            java.util.Arrays.stream(SyncTable.values()).filter(SyncTable::sensitive).map(SyncTable::key).toList());
    }

    @Test
    void migrationCreatesTheSettingsTableSafelyAndKeepsOneRowPerOrganizationAndTable() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/V31__release_7.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS sync_table_config"));
        assertTrue(sql.contains("PRIMARY KEY (organization_id, table_name)"));
        for (String col : new String[]{"active", "expiration_period", "description", "created_at", "updated_at"})
            assertTrue(sql.contains(col), col);
    }

    @Test
    void apiLevelIsSeven() {
        assertEquals(7, com.waha.config.ApiLevel.CURRENT);
    }
}
