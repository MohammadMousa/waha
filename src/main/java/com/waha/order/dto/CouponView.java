package com.waha.order.dto;

import java.math.BigDecimal;

// The coupon an order or quote used, with the discount it gave (part of the total discount).
public record CouponView(String code, String offerName, BigDecimal discountAmount) {}
