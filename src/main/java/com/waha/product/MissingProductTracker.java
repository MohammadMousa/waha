package com.waha.product;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// Counts scans of barcodes that are not in the catalog, in memory, so a kiosk scanning the same unknown barcode
// over and over does not write to the database each time. A timer (the same last_seen_flush_minutes interval)
// and shutdown write one row per store and barcode.
@Component
public class MissingProductTracker {

    private static final Logger log = LoggerFactory.getLogger(MissingProductTracker.class);
    private static final int DEFAULT_FLUSH_MINUTES = 60;

    private record Key(long storeId, String barcode) {}
    private record Entry(long count, Instant first, Instant last, String username) {}

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<Key, Entry> pending = new ConcurrentHashMap<>();
    private volatile Instant lastFlush = Instant.now();

    public MissingProductTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Called on every scan that finds nothing. Never throws. The username is looked up only for the first
    // miss of a barcode in an interval.
    public void record(long storeId, String barcode, Supplier<String> username) {
        try {
            Key key = new Key(storeId, barcode);
            Instant now = Instant.now();
            Entry old = pending.get(key);
            String who = old != null && old.username() != null ? old.username() : safe(username);
            pending.merge(key, new Entry(1, now, now, who),
                (o, n) -> new Entry(o.count() + 1, o.first(), now, n.username() != null ? n.username() : o.username()));
        } catch (RuntimeException e) {
            log.warn("Missing-product count skipped: {}", e.getMessage());
        }
    }

    private static String safe(Supplier<String> s) {
        try { return s.get(); } catch (RuntimeException e) { return null; }
    }

    // Drops counts not yet written for a barcode (it was removed from the report).
    public void discard(long storeId, String barcode) {
        pending.remove(new Key(storeId, barcode));
    }

    public int pendingCount() {
        return pending.size();
    }

    public Instant lastFlushAt() {
        return lastFlush;
    }

    @Scheduled(fixedDelay = 60_000)
    public void flushIfDue() {
        if (Instant.now().isBefore(lastFlush.plusSeconds(flushMinutes() * 60L))) return;
        flush();
    }

    @PreDestroy
    public void flush() {
        lastFlush = Instant.now();
        writePending();
    }

    // The report writes what is waiting first so every row it shows has an id. The timer is left alone.
    public void flushNow() {
        writePending();
    }

    private void writePending() {
        int n = 0;
        for (Key key : List.copyOf(pending.keySet())) {
            Entry e = pending.remove(key);
            if (e == null) continue;
            try {
                jdbc.update(
                    "INSERT INTO product_missing (store_id, barcode, miss_count, first_missed_at, last_missed_at, last_username) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE miss_count = miss_count + VALUES(miss_count), "
                        + "last_missed_at = GREATEST(last_missed_at, VALUES(last_missed_at)), "
                        + "last_username = COALESCE(VALUES(last_username), last_username)",
                    key.storeId(), key.barcode(), e.count(), Timestamp.from(e.first()), Timestamp.from(e.last()), e.username());
                n++;
            } catch (RuntimeException ex) {
                log.warn("Missing-product flush failed for one barcode: {}", ex.getMessage());
                pending.merge(key, e, (o, nu) -> new Entry(o.count() + nu.count(), nu.first(), o.last(), o.username()));
            }
        }
        if (n > 0) log.info("Missing-product flush wrote {} barcode(s)", n);
    }

    // Global (organization 0) property, default 60.
    private int flushMinutes() {
        try {
            String v = jdbc.query(
                "SELECT value FROM system_properties WHERE `key` = 'last_seen_flush_minutes' AND organization_id = 0 LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
            if (v != null) {
                int m = Integer.parseInt(v.trim());
                if (m >= 1 && m <= 1440) return m;
            }
        } catch (RuntimeException ignored) { }
        return DEFAULT_FLUSH_MINUTES;
    }
}
