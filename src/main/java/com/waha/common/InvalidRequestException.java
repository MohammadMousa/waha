package com.waha.common;

// For malformed/incomplete requests that aren't about a specific missing
// resource (that's OrderNotFoundException) or a business-rule conflict
// (ProductNotSellableException, OrderNotPayableException) - a plain 400.
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
