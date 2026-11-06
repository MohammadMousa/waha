package com.waha.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// One offline order with its lines and its successful payments (see docs/offline.md section 6).
// There is no order number in it: the server assigns that on push. orderReference is the short code the
// kiosk made when it created the order (optional: the server makes one when it is missing).
public record OfflineOrderRequest(
    String orderId, String orderReference, Long storeId, String username, String status, String currency,
    BigDecimal taxRate, BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
    Instant createdAt, List<Item> items, List<Payment> payments
) {
    public record Item(Integer lineNo, Long productId, Integer quantity, BigDecimal unitPrice) {}

    public record Payment(String paymentId, String provider, String outcome, String providerReference,
                          Instant createdAt, String authCode, JsonNode notes, Boolean lateApproval) {}
}
