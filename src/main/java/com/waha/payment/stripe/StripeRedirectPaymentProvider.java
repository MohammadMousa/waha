package com.waha.payment.stripe;

import com.waha.common.InvalidRequestException;
import com.waha.payment.PaymentSession;
import com.waha.payment.PaymentStatus;
import com.waha.payment.RedirectPaymentProvider;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// Ported from commerce-platform's StripePaymentProvider - same shape, same
// field usage, package and product-name references updated only.
@Component("stripe")
public class StripeRedirectPaymentProvider implements RedirectPaymentProvider {

    @Value("${stripe.secret-key:}")
    private String secretKey;

    @PostConstruct
    void init() {
        Stripe.apiKey = secretKey;
    }

    @Override
    public PaymentSession createSession(BigDecimal amount, String currency, String reference, String successUrl, String cancelUrl) {
        long unitAmount = amount.multiply(BigDecimal.valueOf(100)).longValueExact(); // Stripe wants the smallest currency unit (cents)

        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(successUrl + "?session_id={CHECKOUT_SESSION_ID}")
            .setCancelUrl(cancelUrl)
            .putMetadata("reference", reference)
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(currency.toLowerCase())
                            .setUnitAmount(unitAmount)
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName("Waha order " + reference)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();

        try {
            Session session = Session.create(params);
            return new PaymentSession(session.getId(), session.getUrl());
        } catch (com.stripe.exception.InvalidRequestException e) {
            if ("amount_too_small".equals(e.getCode())) {
                throw new InvalidRequestException(
                    "The order total is too small for card payment. Please add more items or use another payment method.");
            }
            throw new InvalidRequestException("Stripe: " + e.getMessage());
        } catch (StripeException e) {
            throw new RuntimeException("Stripe session creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentStatus checkStatus(String providerReference) {
        try {
            Session session = Session.retrieve(providerReference);
            if ("paid".equals(session.getPaymentStatus())) return PaymentStatus.PAID;
            if ("open".equals(session.getStatus())) return PaymentStatus.PENDING;
            return PaymentStatus.FAILED;
        } catch (StripeException e) {
            return PaymentStatus.FAILED;
        }
    }
}
