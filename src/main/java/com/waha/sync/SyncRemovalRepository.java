package com.waha.sync;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Rows that were really deleted on the server, so devices can delete their copies (docs/offline.md, "Removals").
@Repository
public class SyncRemovalRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SyncRemovalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long latestId(long orgId) {
        Long v = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM sync_removals WHERE organization_id = :o",
            Map.of("o", orgId), Long.class);
        return v == null ? 0 : v;
    }

    public long purgedUpTo() {
        Long v = jdbc.queryForObject("SELECT purged_up_to_id FROM sync_removals_state WHERE id = 1",
            Map.of(), Long.class);
        return v == null ? 0 : v;
    }

    public record Removal(long id, Map<String, Object> fields) {}

    public List<Removal> after(long orgId, long afterId, int limit) {
        return jdbc.query(
            "SELECT id, table_name, row_id, removed_at FROM sync_removals " +
            "WHERE organization_id = :o AND id > :a ORDER BY id LIMIT :l",
            new MapSqlParameterSource("o", orgId).addValue("a", afterId).addValue("l", limit),
            (rs, i) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", rs.getLong("id"));
                f.put("table", rs.getString("table_name"));
                f.put("rowId", rs.getLong("row_id"));
                f.put("removedAt", rs.getTimestamp("removed_at").toInstant());
                return new Removal(rs.getLong("id"), f);
            });
    }

    // Deletes rows older than the cutoff and remembers the highest deleted id. Returns the number deleted.
    public int purgeOlderThan(Instant cutoff) {
        Long top = jdbc.queryForObject(
            "SELECT MAX(id) FROM sync_removals WHERE removed_at < :c",
            Map.of("c", java.sql.Timestamp.from(cutoff)), Long.class);
        if (top == null) return 0;
        jdbc.update("UPDATE sync_removals_state SET purged_up_to_id = GREATEST(purged_up_to_id, :t) WHERE id = 1",
            Map.of("t", top));
        return jdbc.update("DELETE FROM sync_removals WHERE id <= :t", Map.of("t", top));
    }
}
