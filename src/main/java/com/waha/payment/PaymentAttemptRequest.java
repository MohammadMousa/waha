package com.waha.payment;

import java.math.BigDecimal;
import java.util.Map;

// metadata is a small, provider-specific extension point (e.g. the Phase 1
// SimulatedPaymentProvider reads a "simulateOutcome" hint from it) so the
// core interface doesn't need to change shape as providers change.
public record PaymentAttemptRequest(String orderId, BigDecimal amount, String currency, String reference, Map<String, String> metadata) {}
