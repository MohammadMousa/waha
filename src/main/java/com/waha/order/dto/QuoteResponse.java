package com.waha.order.dto;

import java.math.BigDecimal;
import java.util.List;

public record QuoteResponse(BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<OrderItemView> items) {}
