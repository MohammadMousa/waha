package com.waha.order;

// Thrown when offers bring a non-empty cart down to zero (or below) - a 100%
// offer, or a fixed offer larger than the cart. Maps to a 422 with a stable
// error code so a client can show its own message.
public class OrderTotalNotPositiveException extends RuntimeException {
    public static final String CODE = "ORDER_TOTAL_NOT_POSITIVE";

    public OrderTotalNotPositiveException() {
        super("Order total must be greater than zero");
    }
}
