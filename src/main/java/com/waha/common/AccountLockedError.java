package com.waha.common;

public record AccountLockedError(
    String code,
    String message,
    long retry_after_seconds
) {
    public static AccountLockedError of(long retryAfterSeconds) {
        return new AccountLockedError("ACCOUNT_LOCKED",
            "Too many failed attempts. Account temporarily locked.", retryAfterSeconds);
    }
}
