package com.waha.order.dto;

import com.waha.payment.dto.PaymentRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
    String orderId,
    Long displayId,        // human-readable order number (null for pre-V8 orders)
    long storeId,
    boolean wasCreated,
    String status,
    String username,
    BigDecimal subtotal,
    BigDecimal tax,
    BigDecimal total,
    BigDecimal taxRate,
    String currency,
    String paymentReference,
    String paymentMethod,  // provider key of the most recent successful payment (null if unpaid)
    String invoiceUrl,
    Instant createdAt,
    List<OrderItemView> items,
    List<PaymentRecord> payments  // all payment attempts, newest first
) {}
