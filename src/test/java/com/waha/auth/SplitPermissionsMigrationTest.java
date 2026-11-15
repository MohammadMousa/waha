package com.waha.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitPermissionsMigrationTest {

    @Test
    void v28CopiesOldPermissionsToTheNewOnes() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/V28__role_permissions.sql"));
        for (String p : Set.of("VIEW_REPORTS", "EXPORT_REPORTS", "MANAGE_PAYMENT_METHODS",
                               "MANAGE_INTEGRATIONS", "MANAGE_SETTINGS", "VIEW_INVENTORY_REPORTS")) {
            assertTrue(sql.contains("'" + p + "'"), p + " missing from V28");
            Permission.valueOf(p);
        }
        assertTrue(sql.contains("'VIEW_ALL_ORDERS', 'EXPORT_REPORTS'"));
        assertTrue(sql.contains("'MANAGE_STORES',   'MANAGE_SETTINGS'"));
    }

    @Test
    void newPermissionsAreAssignableInCustomRoles() {
        assertTrue(RolePolicy.parsePermissions(java.util.List.of("VIEW_REPORTS", "EXPORT_REPORTS",
            "MANAGE_PAYMENT_METHODS", "MANAGE_INTEGRATIONS", "MANAGE_SETTINGS", "VIEW_INVENTORY_REPORTS")).size() == 6);
    }

    @Test
    void superAdminAndOrganizationOwnerKeepInventoryReports() throws Exception {
        assertTrue(Permission.FIXED_BY_ROLE.get(Role.SUPER_ADMIN).contains(Permission.VIEW_INVENTORY_REPORTS));
        String sql = Files.readString(Path.of("db/migrations/V28__role_permissions.sql"));
        // the owner is seeded with VIEW_ALL_ORDERS, and the migration copies that to VIEW_INVENTORY_REPORTS
        assertTrue(sql.contains("SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_ALL_ORDERS' AS permission"));
        assertTrue(sql.contains("'VIEW_ALL_ORDERS', 'VIEW_INVENTORY_REPORTS'"));
    }
}
