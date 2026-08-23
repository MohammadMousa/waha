package com.waha.payment;

public record PaymentAttemptResult(PaymentStatus status, String providerReference, String detail, String provider) {}
