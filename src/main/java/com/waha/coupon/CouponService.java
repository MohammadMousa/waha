package com.waha.coupon;

import com.waha.offer.OfferClock;
import com.waha.offer.PricingEngine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Checks that a coupon code may be used in a store and turns it into the input the pricing engine needs.
// The minimum order amount and the stacking rule depend on the price after automatic offers, so the order service
// checks them with the priced cart.
@Service
public class CouponService {

    // cap: the most this order can be discounted by the coupon (per-order cap and what is left of the amount limits).
    public record Resolved(Coupon coupon, BigDecimal cap) {
        public PricingEngine.CouponInput input() {
            return new PricingEngine.CouponInput(coupon.rule(), cap);
        }
    }

    private final CouponRepository coupons;
    private final OfferClock clock;

    public CouponService(CouponRepository coupons, OfferClock clock) {
        this.coupons = coupons;
        this.clock = clock;
    }

    // organizationId is the organization of the store. A code that exists only in another organization is
    // reported as not found, so nothing is revealed about other organizations. username may be null (a quote
    // without a customer); the per-user limits then wait until the order is created.
    public Resolved resolve(String code, long storeId, long organizationId, String username) {
        Coupon coupon = coupons.findByCode(organizationId, Coupon.normalize(code))
            .orElseThrow(() -> new CouponException("COUPON_NOT_FOUND", "This coupon code is not valid"));
        if (!coupon.enabled()) throw new CouponException("COUPON_DISABLED", "This coupon is not active");

        LocalDateTime now = clock.now();
        if (coupon.startsAt() != null && now.isBefore(coupon.startsAt()))
            throw new CouponException("COUPON_NOT_YET_VALID", "This coupon is not valid yet");
        if (coupon.expiresAt() != null && now.isAfter(coupon.expiresAt()))
            throw new CouponException("COUPON_EXPIRED", "This coupon has expired");
        if (!coupon.coversStore(storeId))
            throw new CouponException("COUPON_WRONG_STORE", "This coupon cannot be used in this store");

        if (coupon.maxInvoices() != null && coupons.paidInvoices(coupon.id()) >= coupon.maxInvoices())
            throw new CouponException("COUPON_USAGE_LIMIT_REACHED", "This coupon has been used the maximum number of times");
        boolean user = username != null && !username.isBlank();
        if (user && coupon.maxInvoicesPerUser() != null
            && coupons.paidInvoices(coupon.id(), username) >= coupon.maxInvoicesPerUser())
            throw new CouponException("COUPON_USER_LIMIT_REACHED", "You have used this coupon the maximum number of times");

        BigDecimal cap = coupon.maxDiscountPerOrder();
        if (coupon.maxAmount() != null) {
            BigDecimal left = coupon.maxAmount().subtract(coupons.paidAmount(coupon.id()));
            if (left.signum() <= 0) throw new CouponException("COUPON_AMOUNT_EXHAUSTED", "This coupon has no discount left");
            cap = smaller(cap, left);
        }
        if (user && coupon.maxAmountPerUser() != null) {
            BigDecimal left = coupon.maxAmountPerUser().subtract(coupons.paidAmount(coupon.id(), username));
            if (left.signum() <= 0) throw new CouponException("COUPON_USER_AMOUNT_EXHAUSTED", "You have no discount left on this coupon");
            cap = smaller(cap, left);
        }
        return new Resolved(coupon, cap);
    }

    private static BigDecimal smaller(BigDecimal a, BigDecimal b) {
        return a == null || b.compareTo(a) < 0 ? b : a;
    }

    // Net amount after automatic offers, before the coupon and before tax.
    public void checkMinimum(Coupon coupon, BigDecimal subtotalBeforeCoupon) {
        BigDecimal min = coupon.minimumOrderAmount();
        if (min != null && subtotalBeforeCoupon.compareTo(min) < 0) {
            throw new CouponException("COUPON_MINIMUM_ORDER_NOT_MET",
                "The order must be at least " + min.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + " before VAT to use this coupon");
        }
    }

    // A coupon that does not stack is refused when an automatic offer already discounts the cart.
    public void checkStacking(Coupon coupon, BigDecimal automaticDiscount) {
        if (!coupon.allowStackingWithOffers() && automaticDiscount.signum() > 0) {
            throw new CouponException("COUPON_NOT_COMBINABLE", "This coupon cannot be used together with other offers");
        }
    }
}
