package com.waha.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class IntegrationsAdminRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IntegrationsAdminRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countLogs(String entityType, String status) {
        String sql = "SELECT COUNT(*) FROM sync_queue WHERE 1=1" + where(entityType, status) + " ";
        MapSqlParameterSource p = params(entityType, status);
        Long c = jdbc.queryForObject(sql, p, Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> getLogs(String entityType, String status, int page, int size) {
        MapSqlParameterSource p = params(entityType, status)
            .addValue("limit",  size)
            .addValue("offset", (long) page * size);
        String sql = "SELECT id, entity_type, entity_id, operation, status, attempts,"
            + " last_error, store_id, created_at, updated_at"
            + " FROM sync_queue WHERE 1=1"
            + where(entityType, status)
            + " ORDER BY id DESC LIMIT :limit OFFSET :offset";
        return jdbc.queryForList(sql, p);
    }

    private String where(String entityType, String status) {
        StringBuilder sb = new StringBuilder();
        if (entityType != null) sb.append("\n  AND entity_type = :entityType");
        if (status      != null) sb.append("\n  AND status      = :status");
        return sb.toString();
    }

    private MapSqlParameterSource params(String entityType, String status) {
        return new MapSqlParameterSource()
            .addValue("entityType", entityType)
            .addValue("status",     status);
    }
}
