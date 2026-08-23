package com.waha.payment.stripe;

import com.waha.order.OrderService;
import com.waha.payment.PaymentStatus;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Ported from commerce-platform's StripeWebhookController - signature
// verification (Webhook.constructEvent) was real and working there, kept
// exactly as-is here. No auth on this endpoint by design - Stripe's HMAC
// signature over the raw body IS the authentication; requiring anything
// else (an API key, a bearer token) would be redundant and wouldn't match
// what Stripe's webhook delivery actually sends.
@RestController
@RequestMapping("/api/payments/webhooks/stripe")
public class StripeWebhookController {

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    private final OrderService orderService;

    public StripeWebhookController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody String payload, @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            return ResponseEntity.status(400).build(); // not actually from Stripe, or the secret is wrong
        }

        if ("checkout.session.completed".equals(event.getType())) {
            StripeObject obj = event.getDataObjectDeserializer().getObject().orElse(null);
            if (obj instanceof Session session) {
                orderService.confirmPaymentFromWebhook(session.getId(), PaymentStatus.PAID);
            }
        }

        return ResponseEntity.ok().build();
    }
}
