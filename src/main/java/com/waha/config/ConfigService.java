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
            "SELECT value FROM system_properties WHERE `key` = 'publicBaseUrl'",
            rs -> rs.next() ? rs.getString(1) : null
        );

        String resolved = (dbValue != null && !dbValue.isBlank()) ? dbValue : envFallback;
        cachedPublicBaseUrl = resolved;
        return resolved;
    }

    public void setPublicBaseUrl(String url) {
        jdbc.update(
            "INSERT INTO system_properties (`key`, value, description) VALUES ('publicBaseUrl', ?, '') " +
            "ON DUPLICATE KEY UPDATE value = VALUES(value)",
            url
        );
        cachedPublicBaseUrl = (url != null && !url.isBlank()) ? url : envFallback;
    }

    // Key-value GET for ConfigController — returns all system_properties.
    public java.util.Map<String, String> findAllProperties() {
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT `key`, value FROM system_properties",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                props.put(rs.getString("key"), rs.getString("value")));
        return props;
    }
}
