package com.waha.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

// Audit of critical system actions only (a handful of rows per month): setting changes, cache actions,
// lockouts cleared and audit deletes. Writing never fails the action it records.
@Service
public class SystemAuditService {

    private static final Logger log = LoggerFactory.getLogger(SystemAuditService.class);
    private static final int MAX_PAGE_SIZE = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public SystemAuditService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long actorUserId, String actorName, String action, String target,
                       String oldValue, String newValue, String details, String ip) {
        try {
            jdbc.update("INSERT INTO system_audit (actor_user_id, actor_name, action, target, old_value, new_value, details, ip) "
                    + "VALUES (:uid, :name, :action, :target, :old, :new, :details, :ip)",
                new MapSqlParameterSource()
                    .addValue("uid", actorUserId).addValue("name", cut(actorName, 255))
                    .addValue("action", cut(action, 50)).addValue("target", cut(target, 255))
                    .addValue("old", oldValue).addValue("new", newValue)
                    .addValue("details", cut(details, 500)).addValue("ip", cut(ip, 64)));
            log.info("System audit: {} {} by {}", action, target == null ? "" : target, actorName);
        } catch (RuntimeException e) {
            log.warn("System audit write failed ({} {}): {}", action, target, e.getMessage());
        }
    }

    // Newest first. Filters are optional; from / to are dates (inclusive, UTC).
    public Map<String, Object> list(int page, int size, String action, String actor, LocalDate from, LocalDate to) {
        int p = Math.max(0, page);
        int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (action != null && !action.isBlank()) { where.append(" AND action = :action"); params.addValue("action", action.trim()); }
        if (actor != null && !actor.isBlank()) { where.append(" AND actor_name LIKE :actor"); params.addValue("actor", "%" + actor.trim() + "%"); }
        if (from != null) { where.append(" AND created_at >= :from"); params.addValue("from", Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC))); }
        if (to != null) { where.append(" AND created_at < :to"); params.addValue("to", Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))); }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM system_audit" + where, params, Long.class);
        params.addValue("limit", s).addValue("offset", (long) p * s);
        List<Map<String, Object>> items = jdbc.query(
            "SELECT id, created_at, actor_user_id, actor_name, action, target, old_value, new_value, details, ip "
                + "FROM system_audit" + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset", params, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                Timestamp at = rs.getTimestamp("created_at");
                m.put("createdAt", at != null ? at.toInstant().toString() : null);
                long uid = rs.getLong("actor_user_id");
                m.put("actorUserId", rs.wasNull() ? null : uid);
                m.put("actorName", rs.getString("actor_name"));
                m.put("action", rs.getString("action"));
                m.put("target", rs.getString("target"));
                m.put("oldValue", rs.getString("old_value"));
                m.put("newValue", rs.getString("new_value"));
                m.put("details", rs.getString("details"));
                m.put("ip", rs.getString("ip"));
                return m;
            });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("page", p);
        out.put("size", s);
        out.put("total", total == null ? 0 : total);
        return out;
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM system_audit WHERE id = :id", Map.of("id", id));
    }

    // before == null clears everything; otherwise rows created before that date (UTC).
    public int clear(LocalDate before) {
        if (before == null) return jdbc.update("DELETE FROM system_audit", new MapSqlParameterSource());
        return jdbc.update("DELETE FROM system_audit WHERE created_at < :before",
            Map.of("before", Timestamp.from(before.atStartOfDay().toInstant(ZoneOffset.UTC))));
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
