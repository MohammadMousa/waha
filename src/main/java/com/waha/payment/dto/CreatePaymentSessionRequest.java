package com.waha.payment.dto;

// successUrl/cancelUrl are optional - omit either and it defaults to the
// order's invoice view (GET /api/invoices/{id}), a sensible landing page
// even without a real frontend route wired up yet. Pass your own when the
// app has proper in-app routes to return to.
public record CreatePaymentSessionRequest(String provider, String successUrl, String cancelUrl) {}
