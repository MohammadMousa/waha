package com.waha.auth;

import com.waha.common.InvalidRequestException;

import java.util.*;

// Pure rules for creating and editing roles. No database here, so they are easy to test.
final class RolePolicy {

    private RolePolicy() {}

    static final int NAME_MIN = 2;
    static final int NAME_MAX = 50;

    static boolean isBuiltInName(String name) {
        if (name == null) return false;
        for (Role r : Role.values()) if (r.name().equalsIgnoreCase(name.trim())) return true;
        return false;
    }

    // Returns the cleaned name, or throws 400.
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.length() < NAME_MIN || name.length() > NAME_MAX)
            throw new InvalidRequestException("Role name must be " + NAME_MIN + "–" + NAME_MAX + " characters");
        if (isBuiltInName(name))
            throw new InvalidRequestException("That name is used by a built-in role");
        return name;
    }

    // Turns permission names into the enum; unknown names and the super-admin-only ones are refused.
    static Set<Permission> parsePermissions(Collection<String> names) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (names == null) return out;
        for (String n : names) {
            Permission p;
            try { p = Permission.valueOf(n == null ? "" : n.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { throw new InvalidRequestException("Unknown permission: " + n); }
            if (Permission.SUPER_ADMIN_ONLY.contains(p))
                throw new InvalidRequestException(p.name() + " cannot be given to a role");
            out.add(p);
        }
        return out;
    }

    // The caller may only hand out permissions they hold themselves.
    // Returns the first one they do not hold, or null when all are covered.
    static Permission firstNotHeld(Set<String> callerPermissions, Collection<Permission> wanted) {
        for (Permission p : wanted) if (!callerPermissions.contains(p.name())) return p;
        return null;
    }

    // Roles that the assignment API never hands out: they have their own flows.
    static boolean assignableByApi(RoleCatalog.RoleInfo role) {
        if (role.fixed()) return false;
        return !(role.builtIn() && role.name().equals(Role.ORGANIZATION_OWNER.name()));
    }
}
