package com.waha.payment.dto;

// redirectUrl is where to send the customer's browser - a Stripe Checkout
// page or a MyFatoorah Hosted Payment Page, depending on which provider
// was requested.
public record PaymentSessionResponse(String redirectUrl) {}
