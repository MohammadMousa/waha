package com.waha.order.dto;

public record PayOrderResponse(boolean paid, String status, String detail, OrderResponse order) {}
