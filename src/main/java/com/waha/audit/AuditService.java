package com.waha.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// One audit history for the whole platform. scope SYSTEM holds super-admin actions (organization 0);
// scope ORGANIZATION holds offers, coupons and roles of one organization.
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final String SYSTEM = "SYSTEM";
    public static final String ORGANIZATION = "ORGANIZATION";
    public static final List<String> TYPES = List.of("SYSTEM", "OFFER", "COUPON", "ROLE", "USER");
    private static final int MAX_PAGE_SIZE = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Writes one row and throws when it cannot (used where the audit must succeed with the change).
    public void write(String scope, long organizationId, String entityType, String entityId, Long actorUserId,
                      String actorName, String action, String target, String oldValue, String newValue,
                      String details, String ip) {
        String name = actorName != null ? actorName : usernameOf(actorUserId);
        jdbc.update("INSERT INTO audit_log (scope, organization_id, entity_type, entity_id, actor_user_id, actor_name, "
                + "action, target, old_value, new_value, details, ip) "
                + "VALUES (:scope, :org, :type, :eid, :uid, :name, :action, :target, :old, :new, :details, :ip)",
            new MapSqlParameterSource()
                .addValue("scope", scope).addValue("org", organizationId).addValue("type", entityType)
                .addValue("eid", cut(entityId, 64)).addValue("uid", actorUserId).addValue("name", cut(name, 255))
                .addValue("action", cut(action, 50)).addValue("target", cut(target, 255))
                .addValue("old", oldValue).addValue("new", newValue)
                .addValue("details", cut(details, 500)).addValue("ip", cut(ip != null ? ip : currentIp(), 64)));
    }

    // Same as write, but never fails the action it records.
    public void record(String scope, long organizationId, String entityType, String entityId, Long actorUserId,
                       String actorName, String action, String target, String oldValue, String newValue, String details) {
        try {
            write(scope, organizationId, entityType, entityId, actorUserId, actorName, action, target,
                oldValue, newValue, details, null);
            log.info("Audit: {} {} {} by {}", entityType, action, target == null ? "" : target, actorName);
        } catch (RuntimeException e) {
            log.warn("Audit write failed ({} {}): {}", entityType, action, e.getMessage());
        }
    }

    public record Query(String scope, Long organizationId, String entityType, String entityId, String action,
                        String actor, LocalDate from, LocalDate to, int page, int size) {}

    // Newest first. scope is ALL, SYSTEM or ORGANIZATION; organizationId narrows the ORGANIZATION rows.
    public Map<String, Object> list(Query q) {
        int p = Math.max(0, q.page());
        int s = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size()));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (SYSTEM.equals(q.scope())) where.append(" AND scope = 'SYSTEM'");
        else if (ORGANIZATION.equals(q.scope())) where.append(" AND scope = 'ORGANIZATION'");
        if (q.organizationId() != null) { where.append(" AND organization_id = :org"); params.addValue("org", q.organizationId()); }
        if (notBlank(q.entityType())) { where.append(" AND entity_type = :type"); params.addValue("type", q.entityType().trim()); }
        if (notBlank(q.entityId())) { where.append(" AND entity_id = :eid"); params.addValue("eid", q.entityId().trim()); }
        if (notBlank(q.action())) { where.append(" AND action = :action"); params.addValue("action", q.action().trim()); }
        if (notBlank(q.actor())) { where.append(" AND actor_name LIKE :actor"); params.addValue("actor", "%" + q.actor().trim() + "%"); }
        if (q.from() != null) { where.append(" AND created_at >= :from"); params.addValue("from", Timestamp.from(q.from().atStartOfDay().toInstant(ZoneOffset.UTC))); }
        if (q.to() != null) { where.append(" AND created_at < :to"); params.addValue("to", Timestamp.from(q.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))); }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log" + where, params, Long.class);
        params.addValue("limit", s).addValue("offset", (long) p * s);
        List<Map<String, Object>> items = jdbc.query(
            "SELECT id, created_at, scope, organization_id, entity_type, entity_id, actor_user_id, actor_name, action, "
                + "target, old_value, new_value, details, ip FROM audit_log" + where
                + " ORDER BY id DESC LIMIT :limit OFFSET :offset", params, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                Timestamp at = rs.getTimestamp("created_at");
                m.put("createdAt", at != null ? at.toInstant().toString() : null);
                m.put("scope", rs.getString("scope"));
                m.put("organizationId", rs.getLong("organization_id"));
                m.put("entityType", rs.getString("entity_type"));
                m.put("entityId", rs.getString("entity_id"));
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
        out.put("types", TYPES);
        return out;
    }

    // Deletes the given rows; onlyScope (SYSTEM or ORGANIZATION) limits which rows may go, null means any.
    public int delete(Collection<Long> ids, String onlyScope) {
        if (ids == null || ids.isEmpty()) return 0;
        return jdbc.update("DELETE FROM audit_log WHERE id IN (:ids)" + (onlyScope == null ? "" : " AND scope = :scope"),
            new MapSqlParameterSource("ids", new ArrayList<>(ids)).addValue("scope", onlyScope));
    }

    // before == null removes everything in the scope; otherwise rows created before that date (UTC).
    // scope is ALL, SYSTEM or ORGANIZATION.
    public int clear(LocalDate before, String scope) {
        StringBuilder sql = new StringBuilder("DELETE FROM audit_log WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (SYSTEM.equals(scope)) sql.append(" AND scope = 'SYSTEM'");
        else if (ORGANIZATION.equals(scope)) sql.append(" AND scope = 'ORGANIZATION'");
        if (before != null) {
            sql.append(" AND created_at < :before");
            params.addValue("before", Timestamp.from(before.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        return jdbc.update(sql.toString(), params);
    }

    public String usernameOf(Long userId) {
        if (userId == null) return null;
        List<String> r = jdbc.getJdbcTemplate().queryForList("SELECT username FROM users WHERE id = ?", String.class, userId);
        return r.isEmpty() ? null : r.get(0);
    }

    private static String currentIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
                var req = a.getRequest();
                String xff = req.getHeader("X-Forwarded-For");
                return xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : req.getRemoteAddr();
            }
        } catch (RuntimeException ignored) {
            // no request bound (scheduled work)
        }
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
