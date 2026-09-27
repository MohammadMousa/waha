package com.waha.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// Manages system_properties at runtime. publicBaseUrl is cached in-memory
// after first read so order fetches don't hit the DB on every call.
// Empty string in DB = use the WAHA_PUBLIC_BASE_URL env var fallback.
@Service
public class ConfigService {

    private final JdbcTemplate jdbc;

    @Value("${waha.public-base-url}")
    private String envFallback;

    private volatile String cachedPublicBaseUrl;

    public ConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String getPublicBaseUrl() {
        String cached = cachedPublicBaseUrl;
        if (cached != null) return cached;

        String dbValue = jdbc.query(
            "SELECT value FROM system_properties WHERE `key` = 'publicBaseUrl' AND organization_id = 0 LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null
        );

        String resolved = (dbValue != null && !dbValue.isBlank()) ? dbValue : envFallback;
        cachedPublicBaseUrl = resolved;
        return resolved;
    }

    public void setPublicBaseUrl(String url) {
        jdbc.update(
            "INSERT INTO system_properties (organization_id, `key`, value, description) VALUES (0, 'publicBaseUrl', ?, '') " +
            "ON DUPLICATE KEY UPDATE value = VALUES(value)",
            url
        );
        cachedPublicBaseUrl = (url != null && !url.isBlank()) ? url : envFallback;
    }

    // Key-value GET for ConfigController — org-specific overrides win over global (org=0).
    public java.util.Map<String, String> findAllProperties(long orgId) {
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        jdbc.query(
            "SELECT `key`, value FROM system_properties WHERE organization_id IN (0, ?) ORDER BY organization_id DESC",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                props.putIfAbsent(rs.getString("key"), rs.getString("value")),
            orgId);
        return props;
    }

    // Returns only the rows explicitly set for this org (no global merge).
    public java.util.List<java.util.Map<String, Object>> listOrgProperties(long orgId) {
        return jdbc.queryForList(
            "SELECT `key`, value, description FROM system_properties WHERE organization_id = ? ORDER BY `key`",
            orgId);
    }

    public void upsertOrgProperty(long orgId, String key, String value, String description) {
        jdbc.update(
            "INSERT INTO system_properties (organization_id, `key`, value, description) VALUES (?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE value = VALUES(value), description = VALUES(description)",
            orgId, key, value, description != null ? description : "");
    }

    public void deleteOrgProperty(long orgId, String key) {
        jdbc.update(
            "DELETE FROM system_properties WHERE organization_id = ? AND `key` = ?",
            orgId, key);
    }
}
