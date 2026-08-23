package com.waha.auth;

public enum Role {
    SUPER_ADMIN, ADMIN, OPERATOR, CASHIER, REGISTERED, ANONYMOUS;

    // SUPER_ADMIN.includes(OPERATOR) == true: higher ordinal = lower privilege
    public boolean includes(Role other) {
        return this.ordinal() <= other.ordinal();
    }
}
