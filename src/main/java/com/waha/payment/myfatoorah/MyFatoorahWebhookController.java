package com.waha.payment.myfatoorah;

import com.waha.order.OrderService;
import com.waha.payment.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Ported as-is from commerce-platform, including its own honest caveat -
// this was never actually a Waha-specific gap:
//
// MyFatoorah signs webhook payloads with an account-level secret - the
// exact header name and hash scheme should be confirmed against your
// account's webhook settings (https://myfatoorah.readme.io, Webhooks
// section) before relying on this in production. Structurally in place;
// signature verification is the one piece that needs a live account to
// verify against, same as it was in the original.
@RestController
@RequestMapping("/api/payments/webhooks/myfatoorah")
public class MyFatoorahWebhookController {

    private final OrderService orderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MyFatoorahWebhookController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody String payload) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        String invoiceId = json.path("Data").path("InvoiceId").asText(null);
        String status = json.path("Data").path("InvoiceStatus").asText(null);

        if (invoiceId != null && "PAID".equalsIgnoreCase(status)) {
            orderService.confirmPaymentFromWebhook(invoiceId, PaymentStatus.PAID);
        }

        return ResponseEntity.ok().build();
    }
}
