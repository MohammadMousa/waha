package com.waha.payment.dto;

// provider: key identifying the aggregator (stripe, myfatoorah).
// providerMode: the payment_methods.provider value the client selected (REDIRECT or QR_LINK).
//   QR_LINK causes the backend to generate a QR data-uri and return expiresAt.
// successUrl/cancelUrl: optional — default to the order's invoice view.
public record CreatePaymentSessionRequest(String provider, String providerMode, String successUrl, String cancelUrl) {}
