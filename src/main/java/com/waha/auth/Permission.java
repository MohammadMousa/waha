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
    MANAGE_INVENTORY;   // configure settings, approve large transfers, full reports

    // The single authoritative mapping: role → what it can do.
    // Change a role's capabilities here — all endpoints pick it up immediately.
    public static final Map<Role, Set<Permission>> BY_ROLE = Map.of(
        Role.KIOSK,              Set.of(VIEW_PRODUCTS, VIEW_OWN_ORDERS, PROCESS_ORDERS),
        Role.CASHIER,            Set.of(VIEW_PRODUCTS, VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY,
                                        VIEW_ALL_ORDERS, PROCESS_ORDERS,
                                        VIEW_INVENTORY),
        Role.OPERATOR,           Set.of(VIEW_PRODUCTS, EDIT_PRODUCTS, MANAGE_CATEGORIES,
                                        VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY, VIEW_ALL_ORDERS, PROCESS_ORDERS,
                                        EDIT_RESOURCES,
                                        VIEW_INVENTORY, PROCESS_INVENTORY),
        Role.BRANCH_ADMIN,       Set.of(VIEW_PRODUCTS, EDIT_PRODUCTS, MANAGE_CATEGORIES,
                                        VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY, VIEW_ALL_ORDERS, PROCESS_ORDERS,
                                        MANAGE_EMPLOYEES, MANAGE_DEVICES, MANAGE_STORES, EDIT_RESOURCES,
                                        VIEW_INVENTORY, PROCESS_INVENTORY, MANAGE_INVENTORY),
        Role.ORGANIZATION_OWNER, Set.of(VIEW_PRODUCTS, EDIT_PRODUCTS, MANAGE_CATEGORIES,
                                        VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY, VIEW_ALL_ORDERS, PROCESS_ORDERS,
                                        MANAGE_EMPLOYEES, MANAGE_DEVICES, MANAGE_STORES, EDIT_RESOURCES,
                                        VIEW_INVENTORY, PROCESS_INVENTORY, MANAGE_INVENTORY),
        Role.SUPER_ADMIN,        EnumSet.allOf(Permission.class)
    );
}
