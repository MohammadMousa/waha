package com.waha.order;

// Thrown when a /pay call can't proceed: the order is already PAID, or a
// concurrent pay attempt won the race (optimistic-concurrency version
// mismatch in sp_mark_order_paid).
public class OrderNotPayableException extends RuntimeException {
    public OrderNotPayableException(String message) {
        super(message);
    }
}
