package com.waha.auth;

import com.waha.common.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RolePolicyTest {

    @Test
    void customNamesCannotUseABuiltInName() {
        assertThrows(InvalidRequestException.class, () -> RolePolicy.cleanName("cashier"));
        assertThrows(InvalidRequestException.class, () -> RolePolicy.cleanName(" Super_Admin "));
        assertThrows(InvalidRequestException.class, () -> RolePolicy.cleanName("x"));
        assertThrows(InvalidRequestException.class, () -> RolePolicy.cleanName(null));
        assertEquals("Stock clerk", RolePolicy.cleanName("  Stock   clerk "));
    }

    @Test
    void superAdminOnlyAndUnknownPermissionsAreRefused() {
        assertThrows(InvalidRequestException.class, () -> RolePolicy.parsePermissions(List.of("MANAGE_USERS")));
        assertThrows(InvalidRequestException.class, () -> RolePolicy.parsePermissions(List.of("MANAGE_SYSTEM")));
        assertThrows(InvalidRequestException.class, () -> RolePolicy.parsePermissions(List.of("NOPE")));
        assertEquals(Set.of(Permission.VIEW_PRODUCTS, Permission.MANAGE_ROLES),
            RolePolicy.parsePermissions(List.of("view_products", "MANAGE_ROLES")));
    }

    @Test
    void callerCannotHandOutWhatTheyDoNotHold() {
        Set<String> caller = Set.of("VIEW_PRODUCTS", "EDIT_PRODUCTS");
        assertNull(RolePolicy.firstNotHeld(caller, List.of(Permission.VIEW_PRODUCTS)));
        assertEquals(Permission.MANAGE_STORES,
            RolePolicy.firstNotHeld(caller, List.of(Permission.VIEW_PRODUCTS, Permission.MANAGE_STORES)));
    }

    @Test
    void fixedAndOwnerRolesAreNotHandedOutByTheApi() {
        var superAdmin = new RoleCatalog.RoleInfo(1, "SUPER_ADMIN", null, null, Set.of());
        var kiosk      = new RoleCatalog.RoleInfo(2, "KIOSK", null, null, Set.of());
        var owner      = new RoleCatalog.RoleInfo(3, "ORGANIZATION_OWNER", null, null, Set.of());
        var cashier    = new RoleCatalog.RoleInfo(4, "CASHIER", null, null, Set.of());
        var custom     = new RoleCatalog.RoleInfo(5, "Stock clerk", 1L, null, Set.of());
        assertFalse(RolePolicy.assignableByApi(superAdmin));
        assertFalse(RolePolicy.assignableByApi(kiosk));
        assertFalse(RolePolicy.assignableByApi(owner));
        assertTrue(RolePolicy.assignableByApi(cashier));
        assertTrue(RolePolicy.assignableByApi(custom));
    }

    @Test
    void aCustomRoleNamedLikeAFixedOneIsNotFixed() {
        var fake = new RoleCatalog.RoleInfo(9, "KIOSK", 1L, null, Set.of());
        assertFalse(fake.fixed());
        assertFalse(fake.builtIn());
    }
}
