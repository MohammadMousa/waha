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

    public long countLogs(long orgId, String entityType, String status) {
        String sql = "SELECT COUNT(*) FROM sync_queue sq"
            + " JOIN external_systems es ON es.id = sq.system_id"
            + " WHERE es.owner_organization_id = :orgId" + where(entityType, status);
        Long c = jdbc.queryForObject(sql, params(orgId, entityType, status), Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> getLogs(long orgId, String entityType, String status, int page, int size) {
        MapSqlParameterSource p = params(orgId, entityType, status)
            .addValue("limit",  size)
            .addValue("offset", (long) page * size);
        String sql = "SELECT sq.id, sq.entity_type, sq.entity_id, sq.operation, sq.status, sq.attempts,"
            + " sq.last_error, sq.payload, sq.store_id, sq.created_at, sq.updated_at"
            + " FROM sync_queue sq"
            + " JOIN external_systems es ON es.id = sq.system_id"
            + " WHERE es.owner_organization_id = :orgId"
            + where(entityType, status)
            + " ORDER BY sq.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.queryForList(sql, p);
    }

    public record LogRow(long id, String entityType, String status) {}

    public List<LogRow> findLogs(long orgId, List<Long> ids) {
        return jdbc.query(
            "SELECT sq.id, sq.entity_type, sq.status FROM sync_queue sq"
            + " JOIN external_systems es ON es.id = sq.system_id"
            + " WHERE es.owner_organization_id = :orgId AND sq.id IN (:ids)",
            new MapSqlParameterSource().addValue("orgId", orgId).addValue("ids", ids),
            (rs, i) -> new LogRow(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    public int deleteLogs(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("DELETE FROM sync_queue WHERE id IN (:ids)", Map.of("ids", ids));
    }

    private String where(String entityType, String status) {
        StringBuilder sb = new StringBuilder();
        if (entityType != null) sb.append("\n  AND sq.entity_type = :entityType");
        if (status      != null) sb.append("\n  AND sq.status      = :status");
        return sb.toString();
    }

    private MapSqlParameterSource params(long orgId, String entityType, String status) {
        return new MapSqlParameterSource()
            .addValue("orgId",      orgId)
            .addValue("entityType", entityType)
            .addValue("status",     status);
    }
}
