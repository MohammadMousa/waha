package com.waha.order.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

// name: bilingual JSON {"ar":"...","en":"..."} — snapshotted from products
// at the time of the join; locale selection happens client-side.
// unitPrice and lineTotal are the prices before any offer; discountAmount is the line's own offer plus its share
// of the invoice offer, netAmount = lineTotal - discountAmount, taxAmount is tax on the net amount.
// offer is the line's own offer (null when none).
public record OrderItemView(long productId, String barcode, JsonNode name, int quantity, BigDecimal unitPrice,
                            BigDecimal lineTotal, BigDecimal discountAmount, BigDecimal netAmount,
                            BigDecimal taxAmount, AppliedOfferView offer) {}
