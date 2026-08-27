package com.waha.payment.terminal;

import java.math.BigDecimal;

// Projection of a PENDING terminal payment_attempt — what the terminal app polls for.
public record TerminalAttemptView(
    String id,              // payment_attempts.id — used for confirm/cancel
    String orderId,
    BigDecimal amount,
    String currency,
    String status           // PENDING | CONFIRMED | CANCELLED | TIMEOUT
) {}
