package com.waha.cleanup;

import com.waha.config.ConfigService;
import com.waha.resource.ResourceRepository;
import com.waha.sync.SyncRemovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

// Removes old rows from a fixed list of tables. How many days each task keeps is a system setting (0 = off).
// Orders, order items, payments, coupon redemptions and offer applications are never touched. Rows of the
// integration queue are never deleted: only the stored content of finished order rows is emptied, because the
// orders report reads "synced" from those rows. Rows that are pending or failed are never changed.
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private static final int BATCH = 5000;
    private static final int MAX_BATCHES_PER_RUN = 200;
    public static final int MAX_DAYS = 3650;

    public record Task(String key, String label, String description, String settingKey,
                       int defaultDays, int minDays) {}

    public static final List<Task> TASKS = List.of(
        new Task("payment_attempts", "Payment attempts",
            "Expired card, QR and link payment attempts (never one still in progress)",
            "Cleanup_PaymentAttempts_Days", 30, 14),
        new Task("audit_log", "Audit history",
            "Who changed what: system actions, offers, coupons and roles",
            "Cleanup_AuditLog_Days", 14, 14),
        new Task("order_status_history", "Order status history",
            "Time line of order status changes",
            "Cleanup_OrderStatusHistory_Days", 14, 14),
        new Task("sync_queue_payload", "Integration queue content",
            "Empties the stored order content of finished rows; the rows stay. Pending and failed rows are never touched",
            "Cleanup_SyncQueuePayload_Days", 30, 14),
        new Task("device_logs", "Device logs",
            "Diagnostic logs uploaded by devices",
            "Cleanup_DeviceLogs_Days", 30, 14),
        new Task("sync_removals", "Sync removals",
            "List of deleted items that offline devices catch up from; keep longer than any device stays offline",
            "Cleanup_SyncRemovals_Days", 180, 30)
    );

    private final JdbcTemplate jdbc;
    private final ConfigService config;
    private final ResourceRepository resources;
    private final SyncRemovalRepository removals;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CleanupService(JdbcTemplate jdbc, ConfigService config, ResourceRepository resources,
                          SyncRemovalRepository removals) {
        this.jdbc = jdbc;
        this.config = config;
        this.resources = resources;
        this.removals = removals;
    }

    public static Task task(String key) {
        for (Task t : TASKS) if (t.key().equals(key)) return t;
        return null;
    }

    // Days to keep for a task: the saved setting, or the default. Never below the task's minimum (0 = off).
    public int daysFor(Task t) {
        String raw = null;
        try {
            raw = config.getGlobalProperty(t.settingKey());
        } catch (RuntimeException e) {
            log.warn("Cleanup: could not read {}: {}", t.settingKey(), e.getMessage());
        }
        int days = t.defaultDays();
        if (raw != null && !raw.isBlank()) {
            try {
                days = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        if (days <= 0) return 0;
        return Math.min(Math.max(days, t.minDays()), MAX_DAYS);
    }

    // ── status for the admin screen ──────────────────────────────────────────

    public List<Map<String, Object>> status() {
        Map<String, Map<String, Object>> last = new LinkedHashMap<>();
        jdbc.query("SELECT task_key, last_run_at, last_removed, last_source, last_error FROM cleanup_runs", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lastRunAt", rs.getTimestamp("last_run_at").toInstant());
            m.put("lastRemoved", rs.getLong("last_removed"));
            m.put("lastSource", rs.getString("last_source"));
            m.put("lastError", rs.getString("last_error"));
            last.put(rs.getString("task_key"), m);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task t : TASKS) {
            int days = daysFor(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", t.key());
            m.put("label", t.label());
            m.put("description", t.description());
            m.put("setting", t.settingKey());
            m.put("days", days);
            m.put("enabled", days > 0);
            m.put("defaultDays", t.defaultDays());
            m.put("minDays", t.minDays());
            m.put("rows", countRows(t));
            m.put("eligible", days > 0 ? countEligible(t, days) : 0L);
            Map<String, Object> l = last.get(t.key());
            m.put("lastRunAt", l == null ? null : l.get("lastRunAt"));
            m.put("lastRemoved", l == null ? null : l.get("lastRemoved"));
            m.put("lastSource", l == null ? null : l.get("lastSource"));
            m.put("lastError", l == null ? null : l.get("lastError"));
            out.add(m);
        }
        return out;
    }

    private long countRows(Task t) {
        String sql = switch (t.key()) {
            case "payment_attempts"      -> "SELECT COUNT(*) FROM payment_attempts";
            case "audit_log"             -> "SELECT COUNT(*) FROM audit_log";
            case "order_status_history"  -> "SELECT COUNT(*) FROM order_status_history";
            case "sync_queue_payload"    -> "SELECT COUNT(*) FROM sync_queue WHERE entity_type = 'ORDER'";
            case "device_logs"           -> "SELECT COUNT(*) FROM resources WHERE device_id IS NOT NULL";
            case "sync_removals"         -> "SELECT COUNT(*) FROM sync_removals";
            default -> null;
        };
        return sql == null ? 0 : nz(jdbc.queryForObject(sql, Long.class));
    }

    private long countEligible(Task t, int days) {
        String where = switch (t.key()) {
            case "payment_attempts"      -> "FROM payment_attempts WHERE expires_at < NOW() - INTERVAL ? DAY";
            case "audit_log"             -> "FROM audit_log WHERE created_at < NOW() - INTERVAL ? DAY";
            case "order_status_history"  -> "FROM order_status_history WHERE changed_at < NOW() - INTERVAL ? DAY";
            case "sync_queue_payload"    -> "FROM sync_queue WHERE " + SYNC_QUEUE_FILTER;
            case "device_logs"           -> "FROM resources WHERE device_id IS NOT NULL AND created_at < NOW() - INTERVAL ? DAY";
            case "sync_removals"         -> "FROM sync_removals WHERE removed_at < NOW() - INTERVAL ? DAY";
            default -> null;
        };
        return where == null ? 0 : nz(jdbc.queryForObject("SELECT COUNT(*) " + where, Long.class, days));
    }

    // Finished order rows older than N days whose content is not yet emptied.
    private static final String SYNC_QUEUE_FILTER =
        "entity_type = 'ORDER' AND status = 'DONE' AND updated_at < NOW() - INTERVAL ? DAY AND JSON_LENGTH(payload) > 0";

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    // ── running ──────────────────────────────────────────────────────────────

    public record Result(String key, int days, long removed, String error, boolean skipped) {}

    // Runs every task that is turned on. Returns one result per task. Only one run at a time.
    public List<Result> runAll(String source) {
        if (!running.compareAndSet(false, true)) return List.of();
        try {
            List<Result> out = new ArrayList<>();
            for (Task t : TASKS) out.add(runTask(t, source));
            return out;
        } finally {
            running.set(false);
        }
    }

    // Runs one task now. Returns null when another run is in progress.
    public Result runOne(Task t, String source) {
        if (!running.compareAndSet(false, true)) return null;
        try {
            return runTask(t, source);
        } finally {
            running.set(false);
        }
    }

    private Result runTask(Task t, String source) {
        int days = daysFor(t);
        if (days == 0) return new Result(t.key(), 0, 0, null, true);
        long removed = 0;
        String error = null;
        try {
            removed = purge(t, days);
        } catch (RuntimeException e) {
            error = cut(e.getMessage(), 255);
            log.warn("Cleanup {} failed: {}", t.key(), e.getMessage());
        }
        jdbc.update("INSERT INTO cleanup_runs (task_key, last_run_at, last_removed, last_source, last_error) "
                + "VALUES (?, NOW(), ?, ?, ?) ON DUPLICATE KEY UPDATE last_run_at = NOW(), "
                + "last_removed = VALUES(last_removed), last_source = VALUES(last_source), last_error = VALUES(last_error)",
            t.key(), removed, source, error);
        if (removed > 0) log.info("Cleanup {}: {} row(s) older than {} days ({})", t.key(), removed, days, source);
        return new Result(t.key(), days, removed, error, false);
    }

    private long purge(Task t, int days) {
        return switch (t.key()) {
            case "payment_attempts"     -> batched("DELETE FROM payment_attempts WHERE expires_at < NOW() - INTERVAL ? DAY LIMIT " + BATCH, days);
            case "audit_log"            -> batched("DELETE FROM audit_log WHERE created_at < NOW() - INTERVAL ? DAY LIMIT " + BATCH, days);
            case "order_status_history" -> batched("DELETE FROM order_status_history WHERE changed_at < NOW() - INTERVAL ? DAY LIMIT " + BATCH, days);
            // updated_at = updated_at keeps the row's own time, so the report and the age check stay unchanged.
            case "sync_queue_payload"   -> batched("UPDATE sync_queue SET payload = JSON_OBJECT(), updated_at = updated_at WHERE "
                                                   + SYNC_QUEUE_FILTER + " LIMIT " + BATCH, days);
            case "device_logs"          -> resources.deleteLogsOlderThan(Instant.now().minus(days, ChronoUnit.DAYS));
            case "sync_removals"        -> removals.purgeOlderThan(Instant.now().minus(days, ChronoUnit.DAYS));
            default -> 0;
        };
    }

    private long batched(String sql, int days) {
        long total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int n = jdbc.update(sql, days);
            total += n;
            if (n < BATCH) break;
        }
        return total;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
