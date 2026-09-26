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

    private final ConcurrentHashMap<Long, AttemptRecord>   employeeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AttemptRecord>   deviceMap   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AttemptRecord>   userMap     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AttemptRecord> unknownMap  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AttemptRecord> ipMap       = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;

    public static final int      MAX_ATTEMPTS     = 5;
    public static final int      IP_MAX_ATTEMPTS  = 20;
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    public PinLockoutService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Employees ─────────────────────────────────────────────────────────────

    public boolean isEmployeeLocked(long id)    { return isLocked(id, employeeMap, "employees"); }
    public int  recordEmployeeFailure(long id)  { return recordFailure(id, employeeMap, "employees"); }
    public void recordEmployeeSuccess(long id)  { recordSuccess(id, employeeMap, "employees"); }
    public void clearEmployeeLockout(long id)   { recordSuccess(id, employeeMap, "employees"); }
    public long getEmployeeLockSeconds(long id) { return getLockSeconds(id, employeeMap, "employees"); }

    // ── Devices ───────────────────────────────────────────────────────────────

    public boolean isDeviceLocked(long id)    { return isLocked(id, deviceMap, "devices"); }
    public int  recordDeviceFailure(long id)  { return recordFailure(id, deviceMap, "devices"); }
    public void recordDeviceSuccess(long id)  { recordSuccess(id, deviceMap, "devices"); }
    public void clearDeviceLockout(long id)   { recordSuccess(id, deviceMap, "devices"); }
    public long getDeviceLockSeconds(long id) { return getLockSeconds(id, deviceMap, "devices"); }

    // ── Users (in-memory only — users table has no locked_until column) ───────

    public boolean isUserLocked(long id)    { return isLockedMemOnly(id, userMap); }
    public int  recordUserFailure(long id)  { return recordFailureMemOnly(id, userMap); }
    public void recordUserSuccess(long id)  { userMap.remove(id); }
    public long getUserLockSeconds(long id) { return getLockSecondsMemOnly(id, userMap); }

    // ── Unknown usernames (in-memory, keyed by "orgId:username") ─────────────

    public boolean isUnknownLocked(String key)    { return isLockedByKey(key, unknownMap); }
    public int  recordUnknownFailure(String key)  { return recordFailureByKey(key, unknownMap, MAX_ATTEMPTS); }
    public long getUnknownLockSeconds(String key) { return getLockSecondsByKey(key, unknownMap); }

    // ── IP rate limiting (in-memory, keyed by client IP) ─────────────────────

    public boolean isIpLocked(String ip)    { return isLockedByKey(ip, ipMap); }
    public int  recordIpFailure(String ip)  { return recordFailureByKey(ip, ipMap, IP_MAX_ATTEMPTS); }
    public long getIpLockSeconds(String ip) { return getLockSecondsByKey(ip, ipMap); }

    // ── shared logic (DB-backed: employees / devices) ─────────────────────────

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

    private int recordFailure(long id, ConcurrentHashMap<Long, AttemptRecord> map, String table) {
        int[] remaining = {MAX_ATTEMPTS};
        map.compute(id, (key, existing) -> {
            int count = existing != null ? existing.count() + 1 : 1;
            if (count >= MAX_ATTEMPTS) {
                Instant lockedUntil = Instant.now().plus(LOCKOUT_DURATION);
                writeLockedUntil(key, lockedUntil, table);
                remaining[0] = 0;
                return new AttemptRecord(count, lockedUntil);
            }
            remaining[0] = MAX_ATTEMPTS - count;
            return new AttemptRecord(count, null);
        });
        return remaining[0];
    }

    private void recordSuccess(long id, ConcurrentHashMap<Long, AttemptRecord> map, String table) {
        map.remove(id);
        jdbc.update("UPDATE " + table + " SET locked_until = NULL WHERE id = ?", id);
    }

    private long getLockSeconds(long id, ConcurrentHashMap<Long, AttemptRecord> map, String table) {
        AttemptRecord rec = map.get(id);
        if (rec != null && rec.lockedUntil() != null && Instant.now().isBefore(rec.lockedUntil()))
            return Math.max(0, Duration.between(Instant.now(), rec.lockedUntil()).toSeconds());
        Instant dbLock = fetchLockedUntil(id, table);
        if (dbLock != null && Instant.now().isBefore(dbLock))
            return Math.max(0, Duration.between(Instant.now(), dbLock).toSeconds());
        return 0;
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

    // ── shared logic (memory-only: users) ────────────────────────────────────

    private boolean isLockedMemOnly(long id, ConcurrentHashMap<Long, AttemptRecord> map) {
        AttemptRecord rec = map.get(id);
        if (rec != null && rec.lockedUntil() != null) {
            if (Instant.now().isBefore(rec.lockedUntil())) return true;
            map.remove(id);
        }
        return false;
    }

    private int recordFailureMemOnly(long id, ConcurrentHashMap<Long, AttemptRecord> map) {
        int[] remaining = {MAX_ATTEMPTS};
        map.compute(id, (key, existing) -> {
            int count = existing != null ? existing.count() + 1 : 1;
            if (count >= MAX_ATTEMPTS) {
                remaining[0] = 0;
                return new AttemptRecord(count, Instant.now().plus(LOCKOUT_DURATION));
            }
            remaining[0] = MAX_ATTEMPTS - count;
            return new AttemptRecord(count, null);
        });
        return remaining[0];
    }

    private long getLockSecondsMemOnly(long id, ConcurrentHashMap<Long, AttemptRecord> map) {
        AttemptRecord rec = map.get(id);
        if (rec != null && rec.lockedUntil() != null && Instant.now().isBefore(rec.lockedUntil()))
            return Math.max(0, Duration.between(Instant.now(), rec.lockedUntil()).toSeconds());
        return 0;
    }

    // ── String-keyed helpers (unknown usernames + IP) ────────────────────────

    private boolean isLockedByKey(String key, ConcurrentHashMap<String, AttemptRecord> map) {
        AttemptRecord rec = map.get(key);
        if (rec != null && rec.lockedUntil() != null) {
            if (Instant.now().isBefore(rec.lockedUntil())) return true;
            map.remove(key);
        }
        return false;
    }

    private int recordFailureByKey(String key, ConcurrentHashMap<String, AttemptRecord> map, int maxAttempts) {
        int[] remaining = {maxAttempts};
        map.compute(key, (k, existing) -> {
            int count = existing != null ? existing.count() + 1 : 1;
            if (count >= maxAttempts) {
                remaining[0] = 0;
                return new AttemptRecord(count, Instant.now().plus(LOCKOUT_DURATION));
            }
            remaining[0] = maxAttempts - count;
            return new AttemptRecord(count, null);
        });
        return remaining[0];
    }

    private long getLockSecondsByKey(String key, ConcurrentHashMap<String, AttemptRecord> map) {
        AttemptRecord rec = map.get(key);
        if (rec != null && rec.lockedUntil() != null && Instant.now().isBefore(rec.lockedUntil()))
            return Math.max(0, Duration.between(Instant.now(), rec.lockedUntil()).toSeconds());
        return 0;
    }
}
