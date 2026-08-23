package com.waha.auth.dto;

import java.util.Map;
import java.util.Set;

// storeId        — the session's currently selected store (null until POST /api/auth/store is called)
// defaultStoreId — the system-configured fallback from system_properties
// mode           — NORMAL / KIOSK / SHOPPING; null until set via sessionProperties
// properties     — all system_properties key→value pairs (e.g. appName, default_store_id)
// permissions    — resolved permission names for this user at their current store
public record AuthResponse(String token, long userId, String username, Long storeId, Long defaultStoreId, String mode, Map<String, String> properties, Set<String> permissions) {}
