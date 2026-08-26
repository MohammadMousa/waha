package com.waha.payment.method;

import com.fasterxml.jackson.databind.JsonNode;

// What GET /api/payment-methods returns per method. display_name is the raw
// JSON blob ({"ar": "...", "en": "..."}) — locale selection happens
// client-side. provider tells the frontend which flow to use:
//   REDIRECT     = open URL in browser
//   QR_LINK      = show provider QR on kiosk
//   PAYMENT_URL  = show invoice URL as QR for mobile handoff (kiosk only)
//   TERMINAL     = await card reader
//   SIMULATED    = dev-only instant success
// paymentUrl: only set for PAYMENT_URL provider — the web-app base URL the
// kiosk appends /invoice/{orderId} to and encodes as QR.
public record PaymentMethodSummary(long id, String key, JsonNode displayName, String provider, boolean offlineCapable, int sortOrder, String paymentUrl) {}
