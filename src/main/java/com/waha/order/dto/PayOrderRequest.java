package com.waha.order.dto;

// simulateOutcome is a Phase 1 demo hook only - "SUCCESS" (default, or
// omit/null) or "FAIL" to exercise the decline UI. Drop this field once a
// real PaymentProvider is wired in for Phase 3.
public record PayOrderRequest(String simulateOutcome) {}
