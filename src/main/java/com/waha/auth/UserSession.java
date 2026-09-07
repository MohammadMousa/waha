package com.waha.auth;

// mode: set once at login or POST /api/auth/store; null until the client
// sends it. Backend uses it to apply mode-aware validation policy.
// Exactly one of userId/employeeId/deviceId is non-zero/non-null per session type.
public record UserSession(String token, long userId, Long employeeId, Long deviceId,
                          Long storeId, String mode, long organizationId) {

    public boolean isUserSession()     { return userId > 0; }
    public boolean isEmployeeSession() { return employeeId != null; }
    public boolean isDeviceSession()   { return deviceId != null; }
}
