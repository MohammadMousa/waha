package com.waha.product;

// Thrown when a quote/checkout references a product that doesn't exist or
// isn't active - a defensive re-check, since a product could have been
// deactivated between the scan and checkout even though the scan itself
// already validated it.
public class ProductNotSellableException extends RuntimeException {
    public ProductNotSellableException(String message) {
        super(message);
    }
}
