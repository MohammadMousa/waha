package com.waha.payment;

// What a provider hands back after starting a checkout - a reference to
// look it up later, and a URL to send the customer to. Both Stripe
// Checkout and MyFatoorah's Hosted Payment Page fit this same shape,
// which is what makes one interface work for both instead of two separate
// integration paths.
public record PaymentSession(String providerReference, String redirectUrl) {}
