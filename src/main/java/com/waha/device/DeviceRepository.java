package com.waha.device;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DeviceRepository {

    // Returned to the kiosk login flow — enough to authenticate and build the session.
    public record DeviceAuth(long id, long organizationId, long storeId, String pinCode, boolean enabled) {}

    // Lightweight record for session context after login.
    public record Device(long id, long organizationId, long storeId, String username, String deviceType) {}

    // Full view for admin listing.
    public record DeviceAdminView(
        long id, String username, String name,
        String deviceKey, String deviceType,
        long organizationId, long storeId, String storeName,
        boolean enabled, LocalDateTime createdAt,
        Instant lockedUntil,
        int deviceNo, Instant lastPullAt, Instant lastPushAt, Integer pendingOrders, Integer failedOrders
    ) {}

    // What the offline device-config call needs about a device.
    public record SyncInfo(long id, long organizationId, long storeId, int deviceNo) {}

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SimpleJdbcInsert insert;

    public DeviceRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.insert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("devices")
            .usingGeneratedKeyColumns("id")
            .usingColumns("organization_id", "store_id", "device_key",
                          "name", "device_type", "username", "pin_code", "enabled", "device_no");
    }

    // ── Kiosk auth ────────────────────────────────────────────────────────────

    public Optional<DeviceAuth> findAuthRecord(String username, long orgId) {
        List<DeviceAuth> results = namedJdbc.query(
            "SELECT id, organization_id, store_id, pin_code, enabled FROM devices " +
            "WHERE username = :username AND organization_id = :orgId",
            Map.of("username", username, "orgId", orgId),
            (rs, i) -> new DeviceAuth(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("store_id"),
                rs.getString("pin_code"),
                rs.getBoolean("enabled")
            )
        );
        return results.stream().findFirst();
    }

    public Optional<DeviceAuth> findAuthById(long id) {
        List<DeviceAuth> results = namedJdbc.query(
            "SELECT id, organization_id, store_id, pin_code, enabled FROM devices WHERE id = :id",
            Map.of("id", id),
            (rs, i) -> new DeviceAuth(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("store_id"),
                rs.getString("pin_code"),
                rs.getBoolean("enabled")
            )
        );
        return results.stream().findFirst();
    }

    public Optional<Device> findById(long id) {
        List<Device> results = namedJdbc.query(
            "SELECT id, organization_id, store_id, username, device_type FROM devices WHERE id = :id",
            Map.of("id", id),
            (rs, i) -> new Device(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("store_id"),
                rs.getString("username"),
                rs.getString("device_type")
            )
        );
        return results.stream().findFirst();
    }

    // ── admin CRUD ────────────────────────────────────────────────────────────

    public List<DeviceAdminView> findAll(long orgId) {
        return namedJdbc.query("""
            SELECT d.id, d.username, d.name, d.device_key, d.device_type,
                   d.organization_id, d.store_id, s.name AS store_name,
                   d.enabled, d.created_at, d.locked_until,
                   d.device_no, d.last_pull_at, d.last_push_at, d.pending_orders, d.failed_orders
            FROM devices d
            JOIN stores s ON s.id = d.store_id
            WHERE d.organization_id = :orgId
            ORDER BY d.id DESC
            """,
            Map.of("orgId", orgId),
            (rs, i) -> {
                java.sql.Timestamp lockedTs = rs.getTimestamp("locked_until");
                return new DeviceAdminView(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("name"),
                    rs.getString("device_key"),
                    rs.getString("device_type"),
                    rs.getLong("organization_id"),
                    rs.getLong("store_id"),
                    rs.getString("store_name"),
                    rs.getBoolean("enabled"),
                    rs.getObject("created_at", LocalDateTime.class),
                    lockedTs != null ? lockedTs.toInstant() : null,
                    rs.getInt("device_no"),
                    instant(rs.getTimestamp("last_pull_at")),
                    instant(rs.getTimestamp("last_push_at")),
                    nullableInt(rs, "pending_orders"),
                    nullableInt(rs, "failed_orders")
                );
            }
        );
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    // ── device number (1..99, unique per store) ──────────────────────────────

    // Lowest number not used by another device of the store, or -1 when all 99 are taken.
    public int nextFreeDeviceNo(long storeId) {
        List<Integer> used = namedJdbc.query("SELECT device_no FROM devices WHERE store_id = :s ORDER BY device_no",
            Map.of("s", storeId), (rs, i) -> rs.getInt(1));
        int candidate = 1;
        for (int n : used) {
            if (n == candidate) candidate++;
            else if (n > candidate) break;
        }
        return candidate <= 99 ? candidate : -1;
    }

    public boolean deviceNoTaken(long storeId, int deviceNo, long exceptDeviceId) {
        Integer c = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM devices WHERE store_id = :s AND device_no = :n AND id <> :id",
            Map.of("s", storeId, "n", deviceNo, "id", exceptDeviceId), Integer.class);
        return c != null && c > 0;
    }

    public Optional<SyncInfo> findSyncInfo(long id) {
        return namedJdbc.query("SELECT id, organization_id, store_id, device_no FROM devices WHERE id = :id",
            Map.of("id", id),
            (rs, i) -> new SyncInfo(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("store_id"), rs.getInt("device_no"))
        ).stream().findFirst();
    }

    // A null argument keeps the stored value.
    public void updateSyncStatus(long id, Instant lastPullAt, Instant lastPushAt, Integer pending, Integer failed) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id)
            .addValue("pull", lastPullAt == null ? null : java.sql.Timestamp.from(lastPullAt))
            .addValue("push", lastPushAt == null ? null : java.sql.Timestamp.from(lastPushAt))
            .addValue("pending", pending)
            .addValue("failed", failed);
        namedJdbc.update("UPDATE devices SET last_pull_at = COALESCE(:pull, last_pull_at), " +
            "last_push_at = COALESCE(:push, last_push_at), pending_orders = COALESCE(:pending, pending_orders), " +
            "failed_orders = COALESCE(:failed, failed_orders) WHERE id = :id", p);
    }

    public boolean existsByUsername(String username, long orgId) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM devices WHERE username = :username AND organization_id = :orgId",
            Map.of("username", username, "orgId", orgId), Integer.class
        );
        return count != null && count > 0;
    }

    public boolean existsById(long id) {
        Integer count = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM devices WHERE id = :id", Map.of("id", id), Integer.class
        );
        return count != null && count > 0;
    }

    public long create(long orgId, long storeId, String deviceKey, String name,
                       String deviceType, String username, String pinCode, boolean enabled, int deviceNo) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("organization_id", orgId)
            .addValue("store_id",        storeId)
            .addValue("device_key",      deviceKey)
            .addValue("name",            name)
            .addValue("device_type",     deviceType != null ? deviceType : "KIOSK")
            .addValue("username",        username)
            .addValue("pin_code",        pinCode)
            .addValue("enabled",         enabled ? 1 : 0)
            .addValue("device_no",       deviceNo);
        return insert.executeAndReturnKey(p).longValue();
    }

    public void patch(long id, JsonNode body) {
        List<String> set = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        if (body.has("name"))      { set.add("name = :name");         p.addValue("name",    body.get("name").asText()); }
        if (body.has("pinCode"))   { set.add("pin_code = :pinCode");  p.addValue("pinCode", body.get("pinCode").asText()); }
        if (body.has("enabled"))   { set.add("enabled = :enabled");   p.addValue("enabled", body.get("enabled").asBoolean() ? 1 : 0); }
        if (body.has("storeId"))   { set.add("store_id = :sid");      p.addValue("sid",     body.get("storeId").asLong()); }
        if (body.has("deviceType")){ set.add("device_type = :dt");    p.addValue("dt",      body.get("deviceType").asText()); }
        if (body.has("deviceNo"))  { set.add("device_no = :dno");     p.addValue("dno",     body.get("deviceNo").asInt()); }
        if (set.isEmpty()) return;
        namedJdbc.update("UPDATE devices SET " + String.join(", ", set) + " WHERE id = :id", p);
    }

    public void delete(long id) {
        namedJdbc.update("DELETE FROM devices WHERE id = :id", Map.of("id", id));
    }
}
