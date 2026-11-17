package com.waha.audit;

import com.waha.auth.Permission;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditMigrationTest {

    @Test
    void migrationCopiesEveryOldAuditTableBeforeDroppingIt() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/V29__release_5.sql"));
        for (String t : new String[]{"system_audit", "offer_audit", "coupon_audit"}) {
            int copy = sql.indexOf("FROM " + t);
            int drop = sql.indexOf("DROP TABLE " + t);
            assertTrue(copy > 0, t + " is not copied");
            assertTrue(drop > copy, t + " is dropped before it is copied");
        }
    }

    @Test
    void organizationOwnerGetsTheOrganizationAuditPermission() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/V29__release_5.sql"));
        assertTrue(sql.contains("'VIEW_ORGANIZATION_AUDIT'") && sql.contains("'ORGANIZATION_OWNER'"));
        Permission.valueOf("VIEW_ORGANIZATION_AUDIT");
        assertFalse(Permission.SUPER_ADMIN_ONLY.contains(Permission.VIEW_ORGANIZATION_AUDIT));
    }
}
