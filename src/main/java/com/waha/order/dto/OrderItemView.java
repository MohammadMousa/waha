package com.waha.order.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

// name: bilingual JSON {"ar":"...","en":"..."} — snapshotted from products
// at the time of the join; locale selection happens client-side.
public record OrderItemView(long productId, String barcode, JsonNode name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
