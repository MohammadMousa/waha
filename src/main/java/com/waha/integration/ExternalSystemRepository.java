package com.waha.integration;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ExternalSystemRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ExternalSystemRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExternalSystem> findByName(String name) {
        List<ExternalSystem> rows = jdbc.query(
            "SELECT id, name, base_url, api_key, username, customer_override, owner_store_id, enabled, " +
            "last_category_sync_at, last_product_sync_at, created_at, updated_at " +
            "FROM external_systems WHERE name = :name",
            Map.of("name", name),
            (rs, i) -> new ExternalSystem(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getString("api_key"),
                rs.getString("username"),
                rs.getString("customer_override"),
                rs.getObject("owner_store_id", Long.class),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("last_category_sync_at")),
                toInstant(rs.getTimestamp("last_product_sync_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
            )
        );
        return rows.stream().findFirst();
    }

    public ExternalSystem upsert(String name, String baseUrl, String apiKey,
                                  String username, String customerOverride, Long ownerStoreId) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("name",             name);
        params.put("baseUrl",          baseUrl);
        params.put("apiKey",           apiKey != null ? apiKey : "");
        params.put("username",         username != null ? username : "");
        params.put("customerOverride", customerOverride);
        params.put("ownerStoreId",     ownerStoreId);
        jdbc.update(
            "INSERT INTO external_systems (name, base_url, api_key, username, customer_override, owner_store_id) " +
            "VALUES (:name, :baseUrl, :apiKey, :username, :customerOverride, :ownerStoreId) " +
            "ON DUPLICATE KEY UPDATE base_url = VALUES(base_url), " +
            "api_key = IF(:apiKey IS NOT NULL AND :apiKey != '', VALUES(api_key), api_key), " +
            "username = IF(:username IS NOT NULL AND :username != '', VALUES(username), username), " +
            "customer_override = VALUES(customer_override), " +
            "owner_store_id = COALESCE(owner_store_id, VALUES(owner_store_id)), " +
            "enabled = TRUE",
            params
        );
        return findByName(name).orElseThrow();
    }

    public void updateLastCategorySyncAt(long id, Instant time) {
        jdbc.update(
            "UPDATE external_systems SET last_category_sync_at = :t WHERE id = :id",
            Map.of("id", id, "t", Timestamp.from(time))
        );
    }

    public void updateLastProductSyncAt(long id, Instant time) {
        jdbc.update(
            "UPDATE external_systems SET last_product_sync_at = :t WHERE id = :id",
            Map.of("id", id, "t", Timestamp.from(time))
        );
    }

    public Map<String, Integer> queueStats(long systemId) {
        return jdbc.queryForObject(
            "SELECT " +
            "  SUM(status = 'PENDING') AS pending, " +
            "  SUM(status = 'FAILED')  AS failed, " +
            "  SUM(status = 'DONE')    AS done " +
            "FROM sync_queue WHERE system_id = :sid",
            Map.of("sid", systemId),
            (rs, i) -> Map.of(
                "pending", rs.getInt("pending"),
                "failed",  rs.getInt("failed"),
                "done",    rs.getInt("done")
            )
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
