package com.waha.shipping;

public class ShippingNotAvailableException extends RuntimeException {
    public static final String CODE = "SHIPPING_NOT_AVAILABLE";

    public ShippingNotAvailableException(String message) {
        super(message);
    }
}
