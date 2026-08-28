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

    MANAGE_USERS,
    MANAGE_STORES,
    MANAGE_SYSTEM,

    EDIT_RESOURCES;

    // The single authoritative mapping: role → what it can do.
    // Change a role's capabilities here — all endpoints pick it up immediately.
    public static final Map<Role, Set<Permission>> BY_ROLE = Map.of(
        Role.ANONYMOUS,   Set.of(VIEW_PRODUCTS, VIEW_OWN_ORDERS),
        Role.REGISTERED,  Set.of(VIEW_PRODUCTS, VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY),
        Role.CASHIER,     Set.of(VIEW_PRODUCTS, VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY,
                                  VIEW_ALL_ORDERS, PROCESS_ORDERS),
        Role.OPERATOR,    Set.of(VIEW_PRODUCTS, EDIT_PRODUCTS, MANAGE_CATEGORIES,
                                  VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY, VIEW_ALL_ORDERS, PROCESS_ORDERS,
                                  EDIT_RESOURCES),
        Role.ADMIN,       Set.of(VIEW_PRODUCTS, EDIT_PRODUCTS, MANAGE_CATEGORIES,
                                  VIEW_OWN_ORDERS, VIEW_ORDER_HISTORY, VIEW_ALL_ORDERS, PROCESS_ORDERS,
                                  MANAGE_USERS, MANAGE_STORES, EDIT_RESOURCES),
        Role.SUPER_ADMIN, EnumSet.allOf(Permission.class)
    );
}
