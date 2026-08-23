package com.waha.payment.dto;

import java.time.Instant;

public record PaymentRecord(
    String provider,
    String outcome,
    String providerReference,
    String detail,
    Instant attemptedAt
) {}
