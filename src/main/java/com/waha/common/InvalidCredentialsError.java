package com.waha.common;

public record InvalidCredentialsError(
    String code,
    String message,
    int attempts_remaining,
    int max_attempts
) {
    public static InvalidCredentialsError of(int remaining) {
        return new InvalidCredentialsError("INVALID_CREDENTIALS", "Invalid username or PIN.", remaining, 5);
    }

    public static InvalidCredentialsError ofPassword(int remaining) {
        return new InvalidCredentialsError("INVALID_CREDENTIALS", "Invalid username or password.", remaining, 5);
    }
}
