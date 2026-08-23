package com.waha.payment;

// Deliberately narrow, same spirit as commerce-platform's PaymentProvider
// interface: one seam, swap the implementation, OrderService never changes.
//
// Shaped differently on purpose, though - commerce-platform's interface
// (createSession + redirectUrl) models a browser-redirect-to-hosted-checkout
// flow, which fits Stripe Checkout / MyFatoorah's Hosted Payment Page for a
// web storefront. A kiosk is card-present, not a browser redirect, so that
// shape doesn't actually apply here. This interface instead models a single
// synchronous attempt against whatever's plugged in - true for a simulated
// provider, and still reasonable for most real card-terminal SDKs (e.g.
// Stripe Terminal), which is what Phase 3 would implement against this same
// interface without OrderService needing to change.
public interface PaymentProvider {
    PaymentAttemptResult attempt(PaymentAttemptRequest request);
}
