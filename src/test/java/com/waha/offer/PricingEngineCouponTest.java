package com.waha.offer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// The coupon stage: it runs after the automatic offers, on what remains, before tax.
class PricingEngineCouponTest {

    private static final BigDecimal VAT = new BigDecimal("0.15");
    private static final LocalDate D = LocalDate.of(2026, 11, 4);

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static PricingEngine.Line line(long product, int qty, String price) {
        return new PricingEngine.Line(product, qty, bd(price));
    }

    private static Offer global(long id, String mode, String value, String max, String activation) {
        return new Offer(id, 1, "g" + id, Offer.GLOBAL, mode, bd(value), max == null ? null : bd(max),
            D, D, true, "ALL", Set.of(), activation);
    }

    private static Offer product(long id, String mode, String value, String activation, Long... products) {
        return new Offer(id, 1, "p" + id, Offer.PRODUCT, mode, bd(value), null, D, D, true, "ALL", Set.of(products), activation);
    }

    private static PricingEngine.CouponInput coupon(Offer o, String maxPerOrder) {
        return new PricingEngine.CouponInput(o, maxPerOrder == null ? null : bd(maxPerOrder));
    }

    @Test
    void withoutACouponThePriceIsUnchanged() {
        var lines = List.of(line(1, 2, "10.00"), line(2, 1, "3.50"));
        var auto = List.of(global(1, "PERCENTAGE", "10", null, Offer.AUTO));
        var plain = PricingEngine.price(lines, auto, VAT);
        var withNull = PricingEngine.price(lines, auto, VAT, null);
        assertEquals(plain.total(), withNull.total());
        assertEquals(plain.discountAmount(), withNull.discountAmount());
        assertNull(withNull.couponOffer());
        assertEquals(0, withNull.couponDiscount().signum());
    }

    @Test
    void globalCouponTakesItsDiscountFromWhatTheAutomaticOfferLeft() {
        var r = PricingEngine.price(List.of(line(1, 1, "100.00")),
            List.of(global(1, "PERCENTAGE", "10", null, Offer.AUTO)), VAT,
            coupon(global(2, "PERCENTAGE", "10", null, Offer.COUPON), null));
        assertEquals(bd("90.00"), r.subtotalBeforeCoupon());
        assertEquals(bd("9.00"), r.couponDiscount());          // 10% of 90, not of 100
        assertEquals(bd("81.00"), r.subtotal());
        assertEquals(bd("12.15"), r.tax());
        assertEquals(bd("93.15"), r.total());
        assertEquals(bd("19.00"), r.discountAmount());
        assertEquals(2L, r.couponOffer().id());
        assertEquals(1L, r.globalOffer().id());
    }

    @Test
    void productCouponDiscountsOnlyMatchingLines() {
        var r = PricingEngine.price(List.of(line(1, 2, "10.00"), line(2, 1, "30.00")), List.of(), VAT,
            coupon(product(5, "PERCENTAGE", "50", Offer.COUPON, 1L), null));
        assertEquals(bd("10.00"), r.couponDiscount());
        assertEquals(bd("10.00"), r.lines().get(0).couponShare());
        assertEquals(0, r.lines().get(1).couponShare().signum());
        assertEquals(bd("40.00"), r.subtotal());
    }

    @Test
    void productCouponStacksOnALineThatAlreadyHasAnAutomaticOffer() {
        var r = PricingEngine.price(List.of(line(1, 1, "100.00")),
            List.of(product(4, "PERCENTAGE", "20", Offer.AUTO, 1L)), VAT,
            coupon(product(5, "PERCENTAGE", "50", Offer.COUPON, 1L), null));
        assertEquals(bd("20.00"), r.lines().get(0).ownDiscount());
        assertEquals(bd("40.00"), r.couponDiscount());        // 50% of the remaining 80
        assertEquals(bd("40.00"), r.subtotal());
    }

    @Test
    void couponNeverTakesMoreThanRemains() {
        var r = PricingEngine.price(List.of(line(1, 1, "8.00")), List.of(), VAT,
            coupon(global(2, "AMOUNT", "50", null, Offer.COUPON), null));
        assertEquals(bd("8.00"), r.couponDiscount());
        assertEquals(0, r.total().signum());
    }

    @Test
    void couponCapAndOfferCapBothApplyTheLowerWins() {
        var lines = List.of(line(1, 1, "100.00"));
        var capOnCoupon = PricingEngine.price(lines, List.of(), VAT,
            coupon(global(2, "PERCENTAGE", "50", "30", Offer.COUPON), "20"));
        assertEquals(bd("20.00"), capOnCoupon.couponDiscount());
        var capOnOffer = PricingEngine.price(lines, List.of(), VAT,
            coupon(global(2, "PERCENTAGE", "50", "15", Offer.COUPON), "40"));
        assertEquals(bd("15.00"), capOnOffer.couponDiscount());
    }

    @Test
    void productCouponCapScalesTheLinesDown() {
        var r = PricingEngine.price(List.of(line(1, 1, "50.00"), line(2, 1, "50.00")), List.of(), VAT,
            coupon(product(5, "PERCENTAGE", "50", Offer.COUPON, 1L, 2L), "30"));
        assertEquals(bd("30.00"), r.couponDiscount());
        assertEquals(bd("15.00"), r.lines().get(0).couponShare());
        assertEquals(bd("15.00"), r.lines().get(1).couponShare());
    }

    @Test
    void couponForNoMatchingProductGivesNoDiscount() {
        var r = PricingEngine.price(List.of(line(1, 1, "10.00")), List.of(), VAT,
            coupon(product(5, "PERCENTAGE", "50", Offer.COUPON, 9L), null));
        assertNull(r.couponOffer());
        assertEquals(bd("10.00"), r.subtotal());
    }

    @Test
    void linesAddUpToTheTotalWithAutomaticOffersAndACoupon() {
        var lines = List.of(line(1, 3, "3.33"), line(2, 2, "7.77"), line(3, 7, "0.99"), line(4, 1, "12.49"));
        var auto = List.of(global(1, "PERCENTAGE", "7", null, Offer.AUTO), product(2, "AMOUNT", "1.10", Offer.AUTO, 2L));
        var r = PricingEngine.price(lines, auto, VAT, coupon(global(3, "PERCENTAGE", "13", null, Offer.COUPON), null));
        BigDecimal sum = r.lines().stream().map(l -> l.netAmount().add(l.taxAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(r.total(), sum);
        BigDecimal discounts = r.lines().stream().map(PricingEngine.PricedLine::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(r.discountAmount(), discounts);
        for (var l : r.lines()) assertTrue(l.netAmount().signum() >= 0);
    }
}
