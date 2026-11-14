package com.waha.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

// The one place that answers "what can a role do". SUPER_ADMIN and KIOSK are fixed in code;
// every other role (built-in or custom) reads its permissions from the role_permissions table.
// The whole catalog is small, so it is cached in memory for a short time and cleared on every change.
@Service
public class RoleCatalog {

    public record RoleInfo(long id, String name, Long organizationId, String description, Set<Permission> permissions) {
        public boolean builtIn() { return organizationId == null; }
        public boolean fixed()   { return builtIn() && (name.equals(Role.SUPER_ADMIN.name()) || name.equals(Role.KIOSK.name())); }
    }

    private static final long TTL_MS = 30_000;

    private final JdbcTemplate jdbc;
    private volatile Map<Long, RoleInfo> cache = Map.of();
    private volatile long loadedAt = 0;

    public RoleCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void invalidate() {
        loadedAt = 0;
    }

    public Optional<RoleInfo> find(long roleId) {
        return Optional.ofNullable(snapshot().get(roleId));
    }

    public List<RoleInfo> all() {
        return snapshot().values().stream().sorted(Comparator.comparingLong(RoleInfo::id)).toList();
    }

    // Union of what the given roles allow. Unknown role ids are ignored.
    public Set<String> permissionsOf(Collection<Long> roleIds) {
        Map<Long, RoleInfo> snap = snapshot();
        Set<String> out = new HashSet<>();
        for (Long id : roleIds) {
            RoleInfo r = snap.get(id);
            if (r != null) r.permissions().forEach(p -> out.add(p.name()));
        }
        return out;
    }

    private Map<Long, RoleInfo> snapshot() {
        if (System.currentTimeMillis() - loadedAt > TTL_MS) {
            synchronized (this) {
                if (System.currentTimeMillis() - loadedAt > TTL_MS) {
                    cache = load();
                    loadedAt = System.currentTimeMillis();
                }
            }
        }
        return cache;
    }

    private Map<Long, RoleInfo> load() {
        Map<Long, Set<Permission>> stored = new HashMap<>();
        jdbc.query("SELECT role_id, permission FROM role_permissions", rs -> {
            try {
                stored.computeIfAbsent(rs.getLong("role_id"), k -> EnumSet.noneOf(Permission.class))
                      .add(Permission.valueOf(rs.getString("permission")));
            } catch (IllegalArgumentException ignored) {
                // A permission that no longer exists in code grants nothing.
            }
        });
        Map<Long, RoleInfo> out = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, organization_id, description FROM roles", rs -> {
            long id = rs.getLong("id");
            String name = rs.getString("name");
            long org = rs.getLong("organization_id");
            Long orgId = rs.wasNull() ? null : org;
            Set<Permission> perms;
            if (orgId == null && Role.SUPER_ADMIN.name().equals(name)) {
                perms = Permission.FIXED_BY_ROLE.get(Role.SUPER_ADMIN);
            } else if (orgId == null && Role.KIOSK.name().equals(name)) {
                perms = Permission.FIXED_BY_ROLE.get(Role.KIOSK);
            } else {
                perms = EnumSet.noneOf(Permission.class);
                perms.addAll(stored.getOrDefault(id, Set.of()));
                perms.removeAll(Permission.SUPER_ADMIN_ONLY);   // defence in depth
            }
            out.put(id, new RoleInfo(id, name, orgId, rs.getString("description"), Collections.unmodifiableSet(perms)));
        });
        return Map.copyOf(out);
    }
}
