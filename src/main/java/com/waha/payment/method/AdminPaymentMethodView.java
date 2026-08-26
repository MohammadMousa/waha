package com.waha.payment.method;

import com.fasterxml.jackson.databind.JsonNode;

// What GET /api/admin/payment-methods returns. effectiveActive is the
// resolved enabled state for this store (global if no override, override
// if one exists). hasStoreOverride indicates whether a pms row already
// exists — useful if the UI wants to show "customised" vs "default".
public record AdminPaymentMethodView(long id, String key, JsonNode displayName, String provider, boolean effectiveActive, boolean hasStoreOverride) {}
