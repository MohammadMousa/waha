package com.waha.payment.method;

import com.fasterxml.jackson.databind.JsonNode;

// What GET /api/payment-methods returns per method. display_name is the raw
// JSON blob ({"ar": "...", "en": "..."}) - locale selection happens
// client-side, same as everywhere else. provider tells the frontend which
// flow to use (REDIRECT = show QR / open URL, TERMINAL = await card reader,
// SIMULATED = dev-only instant success).
// offlineCapable: REDIRECT=false (needs internet), TERMINAL=true (POS has
// its own connectivity), SIMULATED=true (no network call). Frontend uses
// this to grey out unavailable methods when the device is offline.
public record PaymentMethodSummary(long id, String key, JsonNode displayName, String provider, boolean offlineCapable, int sortOrder) {}
