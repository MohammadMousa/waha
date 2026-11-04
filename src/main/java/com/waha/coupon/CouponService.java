package com.waha.coupon;

import com.waha.offer.Offer;
import com.waha.offer.OfferClock;
import com.waha.offer.OfferRepository;
import com.waha.offer.PricingEngine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Checks that a coupon code may be used in a store and turns it into the input the pricing engine needs.
// The minimum order amount depends on the price after automatic offers, so the order service checks it.
@Service
public class CouponService {

    public record Resolved(Coupon coupon, Offer offer) {
        public PricingEngine.CouponInput input() {
            return new PricingEngine.CouponInput(offer, coupon.maxDiscountPerOrder());
        }
    }

    private final CouponRepository coupons;
    private final OfferRepository offers;
    private final OfferClock clock;

    public CouponService(CouponRepository coupons, OfferRepository offers, OfferClock clock) {
        this.coupons = coupons;
        this.offers = offers;
        this.clock = clock;
    }

    // organizationId is the organization of the store. A code that exists only in another organization is
    // reported as not found, so nothing is revealed about other organizations.
    public Resolved resolve(String code, long storeId, long organizationId) {
        Coupon coupon = coupons.findByCode(organizationId, Coupon.normalize(code))
            .orElseThrow(() -> new CouponException("COUPON_NOT_FOUND", "This coupon code is not valid"));
        if (!coupon.enabled()) throw new CouponException("COUPON_DISABLED", "This coupon is not active");

        LocalDateTime now = clock.now();
        if (coupon.startsAt() != null && now.isBefore(coupon.startsAt()))
            throw new CouponException("COUPON_NOT_YET_VALID", "This coupon is not valid yet");
        if (coupon.expiresAt() != null && now.isAfter(coupon.expiresAt()))
            throw new CouponException("COUPON_EXPIRED", "This coupon has expired");

        Offer offer = offers.loadById(coupon.offerId()).orElse(null);
        if (offer == null || offer.organizationId() != organizationId || !offer.enabled()
            || Offer.AUTO.equals(offer.activationMode()) || !offer.isCurrent(clock.today())) {
            throw new CouponException("COUPON_OFFER_UNAVAILABLE", "This coupon cannot be used right now");
        }
        if (!offers.offerCoversStore(offer, storeId))
            throw new CouponException("COUPON_WRONG_STORE", "This coupon cannot be used in this store");

        if (coupon.usageLimit() != null && coupons.paidUsage(coupon.id()) >= coupon.usageLimit())
            throw new CouponException("COUPON_USAGE_LIMIT_REACHED", "This coupon has been used the maximum number of times");
        return new Resolved(coupon, offer);
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
}
