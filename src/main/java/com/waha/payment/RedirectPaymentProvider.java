package com.waha.payment;

import java.math.BigDecimal;

// Deliberately narrow, mirrors what both Stripe and MyFatoorah can do
// identically: redirect to a hosted payment page, then verify by
// reference. Separate from PaymentProvider (Kiosk's synchronous
// card-present model) on purpose - a redirect-based web checkout and an
// in-person terminal tap are genuinely different shapes; forcing both into
// one interface would be the wrong kind of abstraction, not a
// simplification. Ported from a working, sandbox-tested implementation in
// commerce-platform, not designed from scratch here.
public interface RedirectPaymentProvider {
    PaymentSession createSession(BigDecimal amount, String currency, String reference, String successUrl, String cancelUrl);
    PaymentStatus checkStatus(String providerReference);
}
