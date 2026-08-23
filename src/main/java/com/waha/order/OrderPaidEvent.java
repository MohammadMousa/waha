package com.waha.order;

public record OrderPaidEvent(String orderId, Long storeId, String currency) {}
