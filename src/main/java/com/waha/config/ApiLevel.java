package com.waha.config;

// Version of the backend's client-facing features. Returned as "api_level" by the public config endpoint.
// Raise by 1 in any release that adds something the dashboard or kiosk depends on, and describe it in
// docs/api-levels.md. A backend older than level 1 does not return the field at all (treat as 0).
public final class ApiLevel {

    public static final int CURRENT = 3;

    private ApiLevel() {}
}
