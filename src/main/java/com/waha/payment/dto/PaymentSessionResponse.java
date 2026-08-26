package com.waha.payment.dto;

import java.time.Instant;

// redirectUrl: where the customer's browser goes (Stripe / MyFatoorah hosted page).
// qrCodeDataUri: base64 PNG of the same URL — kiosk renders this on-screen for
//   the customer to scan on their phone. Null for non-kiosk flows (QR_LINK provider
//   always populates it; REDIRECT provider leaves it null).
// expiresAt: when the payment link expires (15 min from creation). Null when
//   qrCodeDataUri is null.
public record PaymentSessionResponse(String redirectUrl, String qrCodeDataUri, Instant expiresAt) {

    // Convenience factory for the non-QR (normal/shopping) redirect flow.
    public static PaymentSessionResponse redirect(String redirectUrl) {
        return new PaymentSessionResponse(redirectUrl, null, null);
    }
}
