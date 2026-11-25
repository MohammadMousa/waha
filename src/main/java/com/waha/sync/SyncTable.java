package com.waha.sync;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

// The tables a device pulls (docs/sync-combined-pull.md). The key is the name used in the feeds and in the settings.
// "sensitive" tables make devices work on stale data for selling when they are switched off or cooled, so the dashboard warns.
public enum SyncTable {
    PRODUCTS("products", true, "Products, prices and flags"),
    BARCODES("barcodes", true, "Every barcode of every product"),
    CATEGORIES("categories", false, "Product categories"),
    PAYMENT_METHODS("payment-methods", true, "Payment methods (global)"),
    STORE_PAYMENT_METHODS("store-payment-methods", true, "Payment methods switched on or off per store"),
    PRODUCT_IMAGES("product-images", false, "Product gallery images"),
    REMOVALS("removals", true, "Deleted products, barcodes, images and categories, so devices delete their copies");

    private final String key;
    private final boolean sensitive;
    private final String description;

    SyncTable(String key, boolean sensitive, String description) {
        this.key = key;
        this.sensitive = sensitive;
        this.description = description;
    }

    public String key() { return key; }
    public boolean sensitive() { return sensitive; }
    public String defaultDescription() { return description; }

    // The tables that have a feed (everything except removals), in the order the combined pull fills them.
    public static final List<SyncTable> FEEDS = Arrays.stream(values()).filter(t -> t != REMOVALS).toList();

    public static Optional<SyncTable> byKey(String key) {
        return Arrays.stream(values()).filter(t -> t.key.equals(key)).findFirst();
    }

    // Same, but only the tables that have a feed (the single feed call has no "removals" table name).
    public static Optional<SyncTable> feedByKey(String key) {
        return byKey(key).filter(t -> t != REMOVALS);
    }
}
