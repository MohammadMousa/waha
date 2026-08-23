package com.waha.payment.myfatoorah;

import com.waha.payment.PaymentSession;
import com.waha.payment.PaymentStatus;
import com.waha.payment.RedirectPaymentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

// Ported from commerce-platform's MyFatoorahPaymentProvider - preserved
// as-is, including its own hard-won notes below, since those came from a
// real sandbox account, not documentation:
//
// Uses MyFatoorah's v2 ExecutePayment/GetPaymentStatus - not v3. v3
// (/v3/payments) was the initial implementation, reconstructed from docs
// and never sandbox-verified; it 404s on this account/endpoint shape. v2
// is confirmed working against a real sandbox call (ExecutePayment -> 200
// with a real PaymentURL, GetPaymentStatus -> 200 with a real
// InvoiceStatus). Field names below (InvoiceId, PaymentURL - capital URL,
// InvoiceStatus) are copied directly from those verified responses, not
// inferred.
//
// v2's ExecutePayment commits to one specific payment method up front
// (unlike Stripe Checkout's own hosted method picker) - PaymentMethodId 2
// (VISA/MASTER) is hardcoded as the default here since Waha's UI doesn't
// offer a method picker. This is account-specific - re-verify if this ever
// runs against a different MyFatoorah account than the one this was
// proven against. InitiatePayment (which lists all available methods) is
// skipped entirely - only needed for a method-picker UI, which this
// integration doesn't have.
@Component("myfatoorah")
public class MyFatoorahRedirectPaymentProvider implements RedirectPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MyFatoorahRedirectPaymentProvider.class);
    private static final int DEFAULT_PAYMENT_METHOD_ID = 2; // VISA/MASTER

    @Value("${myfatoorah.api-key:}")
    private String apiKey;

    @Value("${myfatoorah.base-url:}")
    private String baseUrl;

    private final RestTemplate restTemplate;

    public MyFatoorahRedirectPaymentProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Override
    public PaymentSession createSession(BigDecimal amount, String currency, String reference, String successUrl, String cancelUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("PaymentMethodId", DEFAULT_PAYMENT_METHOD_ID);
        body.put("CustomerName", "Waha Customer"); // required field; not collected at this point in the flow
        body.put("CustomerReference", reference);
        body.put("InvoiceValue", amount);
        body.put("CurrencyIso", currency);
        body.put("CallBackUrl", successUrl);
        body.put("ErrorUrl", cancelUrl);

        try {
            log.info("MyFatoorah ExecutePayment request: {}", body);
            JsonNode response = restTemplate.exchange(
                baseUrl + "/v2/ExecutePayment", HttpMethod.POST, new HttpEntity<>(body, authHeaders()), JsonNode.class
            ).getBody();
            log.info("MyFatoorah ExecutePayment response: {}", response);

            JsonNode data = response.path("Data");
            String invoiceId = data.path("InvoiceId").asText(); // numeric in the response; asText() handles the conversion
            String paymentUrl = data.path("PaymentURL").asText();
            log.info("MyFatoorah session created - invoiceId={} reference={}", invoiceId, reference);
            return new PaymentSession(invoiceId, paymentUrl);
        } catch (HttpStatusCodeException e) {
            throw new RuntimeException("MyFatoorah createSession failed (" + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public PaymentStatus checkStatus(String providerReference) {
        Map<String, Object> body = Map.of("Key", providerReference, "KeyType", "InvoiceId");

        try {
            log.info("MyFatoorah GetPaymentStatus request: {}", body);
            JsonNode response = restTemplate.exchange(
                baseUrl + "/v2/GetPaymentStatus", HttpMethod.POST, new HttpEntity<>(body, authHeaders()), JsonNode.class
            ).getBody();
            log.info("MyFatoorah GetPaymentStatus response: {}", response);

            String status = response.path("Data").path("InvoiceStatus").asText();
            // MyFatoorah's own docs confirm InvoiceStatus stays "Pending" for
            // FAILED/INPROGRESS/AUTHORIZE/CANCELED transactions alike - it's
            // never a distinct "Failed" at the invoice level. The real reason
            // lives in InvoiceTransactions[].Error - surfaced here so a
            // decline shows an actual reason instead of "still pending".
            if ("Paid".equalsIgnoreCase(status)) return PaymentStatus.PAID;
            if ("Pending".equalsIgnoreCase(status)) {
                JsonNode transactions = response.path("Data").path("InvoiceTransactions");
                if (transactions.isArray() && transactions.size() > 0) {
                    JsonNode latest = transactions.get(transactions.size() - 1);
                    String txStatus = latest.path("TransactionStatus").asText();
                    if ("Failed".equalsIgnoreCase(txStatus)) {
                        String reason = latest.path("Error").asText("declined");
                        throw new RuntimeException("Card declined: " + reason);
                    }
                }
                return PaymentStatus.PENDING;
            }
            return PaymentStatus.FAILED;
        } catch (HttpStatusCodeException e) {
            throw new RuntimeException("MyFatoorah checkStatus failed (" + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }
    }
}
