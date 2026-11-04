package com.waha.order.dto;

import java.math.BigDecimal;
import java.util.List;

// subtotal is the net amount after all offers; total = subtotal + tax.
// discountAmount is the total discount (automatic offers and the coupon); coupon is null when none was used; invoiceOffer is the invoice-level offer (null when none).
public record QuoteResponse(BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<OrderItemView> items,
                            BigDecimal discountAmount, AppliedOfferView invoiceOffer, CouponView coupon) {}
