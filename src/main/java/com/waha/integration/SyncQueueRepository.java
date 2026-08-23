package com.waha.integration;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SyncQueueRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SyncQueueRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(long systemId, String entityType, String entityId,
                        String operation, String payload, Long storeId) {
        Map<String, Object> p = new HashMap<>();
        p.put("sid",       systemId);
        p.put("type",      entityType);
        p.put("eid",       entityId);
        p.put("op",        operation);
        p.put("payload",   payload);
        p.put("storeId",   storeId);
        jdbc.update(
            "INSERT INTO sync_queue (system_id, entity_type, entity_id, operation, payload, store_id) " +
            "VALUES (:sid, :type, :eid, :op, :payload, :storeId)",
            p
        );
    }

    // Returns PENDING items oldest-first, up to limit, skipping rows that
    // have been attempted recently (exponential backoff: 30s × 2^attempts).
    public List<SyncQueueItem> findReadyToProcess(int limit) {
        return jdbc.query(
            "SELECT id, system_id, entity_type, entity_id, operation, payload, status, " +
            "       attempts, last_error, store_id, created_at, updated_at " +
            "FROM sync_queue " +
            "WHERE status = 'PENDING' " +
            "  AND (attempts = 0 OR updated_at <= NOW() - INTERVAL (30 * POW(2, attempts)) SECOND) " +
            "ORDER BY created_at LIMIT :lim",
            Map.of("lim", limit),
            (rs, i) -> {
                long sv = rs.getLong("store_id");
                Long storeId = rs.wasNull() ? null : sv;
                Timestamp ca = rs.getTimestamp("created_at");
                Timestamp ua = rs.getTimestamp("updated_at");
                return new SyncQueueItem(
                    rs.getLong("id"),
                    rs.getLong("system_id"),
                    rs.getString("entity_type"),
                    rs.getString("entity_id"),
                    rs.getString("operation"),
                    rs.getString("payload"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getString("last_error"),
                    storeId,
                    ca == null ? null : ca.toInstant(),
                    ua == null ? null : ua.toInstant()
                );
            }
        );
    }

    public void markDone(long id) {
        jdbc.update(
            "UPDATE sync_queue SET status = 'DONE', updated_at = NOW() WHERE id = :id",
            Map.of("id", id)
        );
    }

    public void markFailed(long id, String error) {
        jdbc.update(
            "UPDATE sync_queue SET status = 'FAILED', last_error = :err, updated_at = NOW() WHERE id = :id",
            Map.of("id", id, "err", error)
        );
    }

    public void incrementAttempts(long id, String lastError) {
        jdbc.update(
            "UPDATE sync_queue SET attempts = attempts + 1, last_error = :err, updated_at = NOW() WHERE id = :id",
            Map.of("id", id, "err", lastError)
        );
    }
}
