package com.waha.auth.dto;

import java.util.Map;

// sessionProperties: optional {"mode": "NORMAL"|"KIOSK"|"SHOPPING", ...}
// Sent once at login; stored in the session so the backend can apply
// mode-aware validation without the client repeating it on every call.
public record LoginRequest(String username, String password, Map<String, String> sessionProperties) {}
