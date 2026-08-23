package com.waha.integration;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ExternalMappingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ExternalMappingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ExternalMapping> findByExternalId(long systemId, String entityType, String externalId) {
        List<ExternalMapping> rows = jdbc.query(
            "SELECT id, system_id, entity_type, local_id, external_id, store_id, created_at, updated_at " +
            "FROM external_mappings " +
            "WHERE system_id = :sid AND entity_type = :type AND external_id = :eid",
            Map.of("sid", systemId, "type", entityType, "eid", externalId),
            (rs, i) -> map(rs)
        );
        return rows.stream().findFirst();
    }

    public Optional<ExternalMapping> findByLocalId(long systemId, String entityType, String localId) {
        List<ExternalMapping> rows = jdbc.query(
            "SELECT id, system_id, entity_type, local_id, external_id, store_id, created_at, updated_at " +
            "FROM external_mappings " +
            "WHERE system_id = :sid AND entity_type = :type AND local_id = :lid",
            Map.of("sid", systemId, "type", entityType, "lid", localId),
            (rs, i) -> map(rs)
        );
        return rows.stream().findFirst();
    }

    public void save(long systemId, String entityType, String localId, String externalId, Long storeId) {
        Map<String, Object> params = new HashMap<>();
        params.put("sid",    systemId);
        params.put("type",   entityType);
        params.put("lid",    localId);
        params.put("eid",    externalId);
        params.put("storeId", storeId);
        jdbc.update(
            "INSERT INTO external_mappings (system_id, entity_type, local_id, external_id, store_id) " +
            "VALUES (:sid, :type, :lid, :eid, :storeId) " +
            "ON DUPLICATE KEY UPDATE external_id = VALUES(external_id), updated_at = NOW()",
            params
        );
    }

    private ExternalMapping map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long storeIdVal = rs.getLong("store_id");
        Long storeId = rs.wasNull() ? null : storeIdVal;
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new ExternalMapping(
            rs.getLong("id"),
            rs.getLong("system_id"),
            rs.getString("entity_type"),
            rs.getString("local_id"),
            rs.getString("external_id"),
            storeId,
            ca == null ? null : ca.toInstant(),
            ua == null ? null : ua.toInstant()
        );
    }
}
