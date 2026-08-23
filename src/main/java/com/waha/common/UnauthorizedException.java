package com.waha.common;

// Missing, malformed, unknown, or expired session token - always the same
// message regardless of which of those it is, so a caller can't use the
// error text to fish for whether a token almost worked.
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
