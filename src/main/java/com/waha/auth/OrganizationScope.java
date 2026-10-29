package com.waha.auth;

import com.waha.common.InvalidRequestException;

import java.util.List;

// A super-admin has organization 0 and works inside one organization by sending X-Organization-Id on each call.
// Pure helpers for that header and for the rule that a super-admin without it cannot write.
final class OrganizationScope {

    private OrganizationScope() {}

    static final String HEADER = "X-Organization-Id";

    // Writes that stay allowed without the header: they either do not depend on an organization or name it in the body.
    private static final List<String> WRITE_EXEMPT_PREFIXES = List.of(
        "/api/auth/", "/api/admin/system/", "/api/admin/users", "/api/organizations", "/api/config",
        "/api/admin/organization/properties");

    // Null when the header is missing; 400 when it is not a positive whole number.
    static Long parse(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            long id = Long.parseLong(header.trim());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new InvalidRequestException(HEADER + " must be a positive whole number");
        }
    }

    static boolean isWrite(String method) {
        return method != null && !method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD")
            && !method.equalsIgnoreCase("OPTIONS");
    }

    static boolean writeNeedsOrganization(String method, String path) {
        if (!isWrite(method)) return false;
        for (String prefix : WRITE_EXEMPT_PREFIXES) if (path != null && path.startsWith(prefix)) return false;
        return true;
    }
}
