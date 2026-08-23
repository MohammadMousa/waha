package com.waha.payment;

import org.springframework.stereotype.Component;

import java.util.UUID;

// Phase 1 stand-in for a real terminal integration. Reads an optional
// "simulateOutcome" hint out of the request metadata (SUCCESS/FAIL) so the
// Flutter app can demo both the success and decline paths without any real
// payment integration. Defaults to SUCCESS when no hint is given.
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

    @Override
    public PaymentAttemptResult attempt(PaymentAttemptRequest request) {
        String outcome = request.metadata() != null
            ? request.metadata().getOrDefault("simulateOutcome", "SUCCESS")
            : "SUCCESS";

        if ("FAIL".equalsIgnoreCase(outcome)) {
            return new PaymentAttemptResult(PaymentStatus.FAILED, null, "Simulated decline", "simulated");
        }

        return new PaymentAttemptResult(PaymentStatus.PAID, "SIM-" + UUID.randomUUID(), null, "simulated");
    }
}
