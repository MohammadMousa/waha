package com.waha.auth;

import com.waha.common.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationScopeTest {

    @Test
    void headerIsOptionalAndMustBePositive() {
        assertNull(OrganizationScope.parse(null));
        assertNull(OrganizationScope.parse("  "));
        assertEquals(2L, OrganizationScope.parse(" 2 "));
        assertThrows(InvalidRequestException.class, () -> OrganizationScope.parse("0"));
        assertThrows(InvalidRequestException.class, () -> OrganizationScope.parse("-1"));
        assertThrows(InvalidRequestException.class, () -> OrganizationScope.parse("abc"));
    }

    @Test
    void onlyWritesOutsideTheExemptPathsNeedAnOrganization() {
        assertTrue(OrganizationScope.writeNeedsOrganization("POST", "/api/products"));
        assertTrue(OrganizationScope.writeNeedsOrganization("DELETE", "/api/admin/devices/3"));
        assertFalse(OrganizationScope.writeNeedsOrganization("GET", "/api/products"));
        assertFalse(OrganizationScope.writeNeedsOrganization("POST", "/api/auth/logout"));
        assertFalse(OrganizationScope.writeNeedsOrganization("PUT", "/api/admin/system/properties"));
        assertFalse(OrganizationScope.writeNeedsOrganization("POST", "/api/admin/users"));
        assertFalse(OrganizationScope.writeNeedsOrganization("PUT", "/api/config"));
        assertFalse(OrganizationScope.writeNeedsOrganization("PUT", "/api/admin/organization/properties"));
    }
}
