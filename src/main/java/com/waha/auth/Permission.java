package com.waha.auth;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum Permission {
    VIEW_PRODUCTS,
    EDIT_PRODUCTS,
    MANAGE_CATEGORIES,

    VIEW_OWN_ORDERS,
    VIEW_ORDER_HISTORY,
    VIEW_ALL_ORDERS,
    PROCESS_ORDERS,

    MANAGE_USERS,       // admin accounts (users table) — org-owner and above only
    MANAGE_EMPLOYEES,   // POS staff (employees table) — branch-admin and above
    MANAGE_DEVICES,     // kiosk devices (devices table) — branch-admin and above
    MANAGE_STORES,
    MANAGE_SYSTEM,

    EDIT_RESOURCES,

    VIEW_INVENTORY,     // read stock levels, low-stock alerts, inventory history
    PROCESS_INVENTORY,  // perform cycle counts, transfers, returns, visits
    MANAGE_INVENTORY,   // configure settings, approve large transfers, full reports

    VIEW_OFFERS,        // see offers — branch admin, operator and above
    MANAGE_OFFERS,      // create, change, enable and delete offers — client manager (organization owner) and above

    VIEW_COUPONS,       // see coupons and their redemptions — branch admin, operator and above
    MANAGE_COUPONS,     // create, change, enable, generate and delete coupons — client manager (organization owner) and above

    UPLOAD_LOGS,        // upload device diagnostic logs — granted to KIOSK by default

    MANAGE_ROLES,       // create, change and delete the organization's own roles — organization owner and above
    ASSIGN_ROLES;       // give or take away roles for users and employees — organization owner and above

    // Roles whose permissions are fixed in code and never read from the database:
    // SUPER_ADMIN is everything, KIOSK is the device list the kiosk apps rely on.
    // Every other role (built-in or custom) gets its permissions from the role_permissions table.
    public static final Map<Role, Set<Permission>> FIXED_BY_ROLE = Map.of(
        Role.KIOSK,       Set.of(VIEW_PRODUCTS, VIEW_OWN_ORDERS, PROCESS_ORDERS, UPLOAD_LOGS),
        Role.SUPER_ADMIN, EnumSet.allOf(Permission.class)
    );

    // These two can never sit in an editable role: they stay with the fixed SUPER_ADMIN role.
    public static final Set<Permission> SUPER_ADMIN_ONLY = EnumSet.of(MANAGE_USERS, MANAGE_SYSTEM);
}
