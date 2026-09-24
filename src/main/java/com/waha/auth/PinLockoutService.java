package com.waha.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PinLockoutService {

    private record AttemptRecord(int count, Instant lockedUntil) {}

    private final ConcurrentHashMap<Long, AttemptRecord> employeeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AttemptRecord> deviceMap   = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;

    private static final int      MAX_ATTEMPTS     = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    public PinLockoutService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Employees ─────────────────────────────────────────────────────────────

    public boolean isEmployeeLocked(long id) { return isLocked(id, employeeMap, "employees"); }
    public void recordEmployeeFailure(long id) { recordFailure(id, employeeMap, "employees"); }
    public void recordEmployeeSuccess(long id) { recordSuccess(id, employeeMap, "employees"); }
    public void clearEmployeeLockout(long id)  { recordSuccess(id, employeeMap, "employees"); }

    // ── Devices ───────────────────────────────────────────────────────────────

    public boolean isDeviceLocked(long id) { return isLocked(id, deviceMap, "devices"); }
    public void recordDeviceFailure(long id) { recordFailure(id, deviceMap, "devices"); }
    public void recordDeviceSuccess(long id) { recordSuccess(id, deviceMap, "devices"); }
    public void clearDeviceLockout(long id)  { recordSuccess(id, deviceMap, "devices"); }

    // ── shared logic ──────────────────────────────────────────────────────────

    private boolean isLocked(long id, ConcurrentHashMap<Long, AttemptRecord> map, String table) {
        AttemptRecord rec = map.get(id);
        if (rec != null && rec.lockedUntil() != null) {
            if (Instant.now().isBefore(rec.lockedUntil())) return true;
            map.remove(id);
            return false;
        }
        // Cache miss — check DB to handle restarts and multi-instance deployments.
        if (rec == null) {
            Instant dbLock = fetchLockedUntil(id, table);
            if (dbLock != null && Instant.now().isBefore(dbLock)) {
                map.put(id, new AttemptRecord(MAX_ATTEMPTS, dbLock));
                return true;
            }
        }
        return false;
    }

    private void recordFailure(long id, ConcurrentHashMap<Long, AttemptRecord> map, String table) {
        map.compute(id, (key, existing) -> {
            int count = existing != null ? existing.count() + 1 : 1;
            if (count >= MAX_ATTEMPTS) {
                Instant lockedUntil = Instant.now().plus(LOCKOUT_DURATION);
                writeLockedUntil(key, lockedUntil, table);
                return new AttemptRecord(count, lockedUntil);
            }
            return new AttemptRecord(count, null);
        });
    }

    private void recordSuccess(long id, ConcurrentHashMap<Long, AttemptRecord> map, String table) {
        map.remove(id);
        jdbc.update("UPDATE " + table + " SET locked_until = NULL WHERE id = ?", id);
    }

    private Instant fetchLockedUntil(long id, String table) {
        List<Timestamp> rows = jdbc.query(
            "SELECT locked_until FROM " + table + " WHERE id = ?",
            (rs, i) -> rs.getTimestamp("locked_until"), id
        );
        return (rows.isEmpty() || rows.get(0) == null) ? null : rows.get(0).toInstant();
    }

    private void writeLockedUntil(long id, Instant lockedUntil, String table) {
        jdbc.update("UPDATE " + table + " SET locked_until = ? WHERE id = ?",
            Timestamp.from(lockedUntil), id);
    }
}
