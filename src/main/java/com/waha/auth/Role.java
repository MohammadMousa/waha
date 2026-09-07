package com.waha.auth;

public enum Role {
    SUPER_ADMIN, ORGANIZATION_OWNER, BRANCH_ADMIN, OPERATOR, CASHIER, KIOSK;

    // SUPER_ADMIN.includes(OPERATOR) == true: higher ordinal = lower privilege
    public boolean includes(Role other) {
        return this.ordinal() <= other.ordinal();
    }
}
