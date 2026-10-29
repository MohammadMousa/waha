package com.waha.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Keeps each kiosk's last signal in memory so busy devices do not write to the database on every call.
// Reads (the device report) use memory first; a timer and shutdown write the changes to the database.
@Component
public class LastSeenTracker {

    private static final Logger log = LoggerFactory.getLogger(LastSeenTracker.class);
    private static final int DEFAULT_FLUSH_MINUTES = 60;

    public record Seen(Instant at, String appVersion) {}
    private record SessionSeen(long deviceId, Instant at, String appVersion) {}

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, SessionSeen> dirtySessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Seen> byDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Seen> bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionDevice = new ConcurrentHashMap<>();
    private volatile Instant lastFlush = Instant.now();

    public LastSeenTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Called on every authenticated kiosk request. Never throws.
    public void touch(String token, long deviceId, String appVersion) {
        try {
            Instant now = Instant.now();
            dirtySessions.merge(token, new SessionSeen(deviceId, now, appVersion),
                (old, nu) -> new SessionSeen(deviceId, now, nu.appVersion() != null ? nu.appVersion() : old.appVersion()));
            bySession.merge(token, new Seen(now, appVersion),
                (old, nu) -> new Seen(now, nu.appVersion() != null ? nu.appVersion() : old.appVersion()));
            // deviceId 0 = a dashboard or employee session: it has its own last seen, but no device.
            if (deviceId > 0) {
                sessionDevice.put(token, deviceId);
                byDevice.merge(deviceId, new Seen(now, appVersion),
                    (old, nu) -> new Seen(now, nu.appVersion() != null ? nu.appVersion() : old.appVersion()));
            }
        } catch (RuntimeException e) {
            log.warn("Last-seen update skipped: {}", e.getMessage());
        }
    }

    // Login: written straight away, so the version is stored even if the server restarts soon after.
    public void touchAndSave(String token, long deviceId, String appVersion) {
        touch(token, deviceId, appVersion);
        try {
            writeSession(token, new SessionSeen(deviceId, Instant.now(), appVersion));
            dirtySessions.remove(token);
        } catch (RuntimeException e) {
            log.warn("Last-seen save failed: {}", e.getMessage());
        }
    }

    public Optional<Seen> get(long deviceId) {
        return Optional.ofNullable(byDevice.get(deviceId));
    }

    public Optional<Seen> getSession(String token) {
        return Optional.ofNullable(bySession.get(token));
    }

    public void forget(String token) {
        dirtySessions.remove(token);
        bySession.remove(token);
        sessionDevice.remove(token);
    }

    // All sessions of a device were ended.
    public void forgetDevice(long deviceId) {
        sessionDevice.entrySet().removeIf(e -> {
            if (e.getValue() != deviceId) return false;
            dirtySessions.remove(e.getKey());
            bySession.remove(e.getKey());
            return true;
        });
    }

    // How many sessions have a last-seen time not yet written to the database.
    public int pendingCount() {
        return dirtySessions.size();
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
        int n = 0;
        for (Map.Entry<String, SessionSeen> e : List.copyOf(dirtySessions.entrySet())) {
            try {
                writeSession(e.getKey(), e.getValue());
                dirtySessions.remove(e.getKey(), e.getValue());
                n++;
            } catch (RuntimeException ex) {
                log.warn("Last-seen flush failed for one session: {}", ex.getMessage());
            }
        }
        if (n > 0) log.info("Last-seen flush wrote {} session(s)", n);
    }

    private void writeSession(String token, SessionSeen s) {
        Timestamp at = Timestamp.from(s.at());
        jdbc.update("UPDATE user_sessions SET last_seen_at = ?, app_version = COALESCE(?, app_version) WHERE token = ?",
            at, s.appVersion(), token);
        if (s.deviceId() > 0) {
            jdbc.update("UPDATE devices SET last_seen_at = ?, app_version = COALESCE(?, app_version) WHERE id = ?",
                at, s.appVersion(), s.deviceId());
        }
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
