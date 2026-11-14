package com.waha.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// Deploying the database-backed roles must not grant or take away anything:
// the seed in V28 has to equal the lists that were fixed in code before (frozen below).
class RolePermissionsSeedTest {

    private static final Map<String, Set<String>> BEFORE = Map.ofEntries(
        Map.entry("CASHIER", Set.of("VIEW_PRODUCTS", "VIEW_OWN_ORDERS", "VIEW_ORDER_HISTORY", "VIEW_ALL_ORDERS", "PROCESS_ORDERS", "VIEW_INVENTORY")),
        Map.entry("OPERATOR", Set.of("VIEW_PRODUCTS", "EDIT_PRODUCTS", "MANAGE_CATEGORIES", "VIEW_OWN_ORDERS", "VIEW_ORDER_HISTORY", "VIEW_ALL_ORDERS", "PROCESS_ORDERS", "EDIT_RESOURCES", "VIEW_INVENTORY", "PROCESS_INVENTORY", "VIEW_OFFERS", "VIEW_COUPONS")),
        Map.entry("BRANCH_ADMIN", Set.of("VIEW_PRODUCTS", "EDIT_PRODUCTS", "MANAGE_CATEGORIES", "VIEW_OWN_ORDERS", "VIEW_ORDER_HISTORY", "VIEW_ALL_ORDERS", "PROCESS_ORDERS", "MANAGE_EMPLOYEES", "MANAGE_DEVICES", "MANAGE_STORES", "EDIT_RESOURCES", "VIEW_INVENTORY", "PROCESS_INVENTORY", "MANAGE_INVENTORY", "VIEW_OFFERS", "VIEW_COUPONS")),
        Map.entry("ORGANIZATION_OWNER", Set.of("VIEW_PRODUCTS", "EDIT_PRODUCTS", "MANAGE_CATEGORIES", "VIEW_OWN_ORDERS", "VIEW_ORDER_HISTORY", "VIEW_ALL_ORDERS", "PROCESS_ORDERS", "MANAGE_EMPLOYEES", "MANAGE_DEVICES", "MANAGE_STORES", "EDIT_RESOURCES", "VIEW_INVENTORY", "PROCESS_INVENTORY", "MANAGE_INVENTORY", "VIEW_OFFERS", "MANAGE_OFFERS", "VIEW_COUPONS", "MANAGE_COUPONS")));

    private static final Set<String> NEW_FOR_OWNER = Set.of("MANAGE_ROLES", "ASSIGN_ROLES");

    @Test
    void seedMatchesTheOldFixedLists() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/V28__role_permissions.sql"));
        Matcher m = Pattern.compile("SELECT '(\\w+)' AS role_name, '(\\w+)' AS permission").matcher(sql);
        Map<String, Set<String>> seeded = new HashMap<>();
        while (m.find()) seeded.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));

        assertEquals(BEFORE.keySet(), seeded.keySet());
        for (var e : BEFORE.entrySet()) {
            Set<String> expected = new TreeSet<>(e.getValue());
            if (e.getKey().equals("ORGANIZATION_OWNER")) expected.addAll(NEW_FOR_OWNER);
            assertEquals(expected, seeded.get(e.getKey()), "role " + e.getKey());
        }
    }

    @Test
    void everySeededPermissionExistsInCodeAndIsNotSuperAdminOnly() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/V28__role_permissions.sql"));
        Matcher m = Pattern.compile("AS role_name, '(\\w+)' AS permission").matcher(sql);
        while (m.find()) {
            Permission p = Permission.valueOf(m.group(1));
            assertFalse(Permission.SUPER_ADMIN_ONLY.contains(p), p.name());
        }
    }
}
