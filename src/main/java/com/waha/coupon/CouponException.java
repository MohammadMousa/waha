package com.waha.coupon;

// A coupon that cannot be used. code is one of the COUPON_* values; the message is safe to show a customer.
public class CouponException extends RuntimeException {

    public final String code;

    public CouponException(String code, String message) {
        super(message);
        this.code = code;
    }
}
