package com.waha.common;

public record RateLimitedError(
    String code,
    String message,
    long retry_after_seconds
) {
    public static RateLimitedError of(long secs) {
        return new RateLimitedError("TOO_MANY_REQUESTS",
            "Too many requests — please try again later.", secs);
    }
}
