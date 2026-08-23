package com.waha.auth;

// mode: set once at login or POST /api/auth/store; null until the client
// sends it. Backend uses it to apply mode-aware validation policy.
public record UserSession(String token, long userId, Long storeId, String mode) {}
