package com.waha.offer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PricingEngineTest {

    private static final BigDecimal VAT = new BigDecimal("0.15");
    private static final LocalDate D = LocalDate.of(2026, 10, 19);

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static PricingEngine.Line line(long product, int qty, String price) {
        return new PricingEngine.Line(product, qty, bd(price));
    }

    private static Offer global(long id, String mode, String value, String max) {
        return new Offer(id, 1, "g" + id, Offer.GLOBAL, mode, bd(value), max == null ? null : bd(max),
            D, D, true, "ALL", Set.of());
    }

    private static Offer product(long id, String mode, String value, Long... products) {
        return new Offer(id, 1, "p" + id, Offer.PRODUCT, mode, bd(value), null, D, D, true, "ALL", Set.of(products));
    }

    @Test
    void noOffers_matchesOldPricing() {
        var r = PricingEngine.price(List.of(line(1, 2, "10.00"), line(2, 1, "3.50")), List.of(), VAT);
        assertEquals(bd("23.50"), r.subtotal());
        assertEquals(bd("3.53"), r.tax());          // 3.00 + 0.53 (0.525 rounds half up)
        assertEquals(bd("27.03"), r.total());
        assertEquals(bd("0.00").compareTo(r.discountAmount()), 0);
    }

    @Test
    void globalPercentage() {
        var r = PricingEngine.price(List.of(line(1, 1, "100.00")), List.of(global(1, "PERCENTAGE", "15", null)), VAT);
        assertEquals(bd("15.00"), r.globalDiscount());
        assertEquals(bd("85.00"), r.subtotal());
        assertEquals(bd("12.75"), r.tax());
        assertEquals(bd("97.75"), r.total());
    }

    @Test
    void globalPercentage_respectsCap() {
        var r = PricingEngine.price(List.of(line(1, 1, "100.00")), List.of(global(1, "PERCENTAGE", "50", "20")), VAT);
        assertEquals(bd("20.00"), r.globalDiscount());
    }

    @Test
    void globalAmount_neverMoreThanSubtotal() {
        var r = PricingEngine.price(List.of(line(1, 1, "8.00")), List.of(global(1, "AMOUNT", "10", null)), VAT);
        assertEquals(bd("8.00"), r.globalDiscount());
        assertEquals(0, r.total().signum());
    }

    @Test
    void productPercentage_onlyMatchingLines() {
        var r = PricingEngine.price(List.of(line(1, 2, "10.00"), line(2, 1, "10.00")),
            List.of(product(1, "PERCENTAGE", "10", 1L)), VAT);
        assertEquals(bd("2.00"), r.lines().get(0).ownDiscount());
        assertEquals(bd("0.00"), r.lines().get(1).ownDiscount());
        assertEquals(bd("28.00"), r.subtotal());
    }

    @Test
    void productAmount_isPerUnit_andCappedAtLine() {
        var r = PricingEngine.price(List.of(line(1, 3, "10.00")), List.of(product(1, "AMOUNT", "4", 1L)), VAT);
        assertEquals(bd("12.00"), r.lines().get(0).ownDiscount());
        var r2 = PricingEngine.price(List.of(line(1, 3, "10.00")), List.of(product(1, "AMOUNT", "15", 1L)), VAT);
        assertEquals(bd("30.00"), r2.lines().get(0).ownDiscount());   // never below zero
        assertEquals(0, r2.subtotal().signum());
    }

    @Test
    void largestProductOfferWins_tieGoesToNewest() {
        var r = PricingEngine.price(List.of(line(1, 1, "100.00")),
            List.of(product(1, "PERCENTAGE", "10", 1L), product(2, "AMOUNT", "25", 1L)), VAT);
        assertEquals(2L, r.lines().get(0).ownOffer().id());
        var tie = PricingEngine.price(List.of(line(1, 1, "100.00")),
            List.of(product(5, "PERCENTAGE", "10", 1L), product(7, "AMOUNT", "10", 1L)), VAT);
        assertEquals(7L, tie.lines().get(0).ownOffer().id());
    }

    @Test
    void productAndGlobalStack_globalWorksOnRemainder() {
        var r = PricingEngine.price(List.of(line(1, 1, "100.00")),
            List.of(product(1, "PERCENTAGE", "10", 1L), global(2, "PERCENTAGE", "10", null)), VAT);
        assertEquals(bd("10.00"), r.lines().get(0).ownDiscount());
        assertEquals(bd("9.00"), r.globalDiscount());            // 10% of 90
        assertEquals(bd("81.00"), r.subtotal());
        assertEquals(bd("19.00"), r.discountAmount());
    }

    @Test
    void globalShareAddsUpExactly_remainderToLargestLine() {
        var r = PricingEngine.price(List.of(line(1, 1, "10.00"), line(2, 1, "10.00"), line(3, 1, "10.01")),
            List.of(global(1, "AMOUNT", "1.00", null)), VAT);
        BigDecimal shares = r.lines().stream().map(PricingEngine.PricedLine::globalShare)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(bd("1.00"), shares);
        BigDecimal nets = r.lines().stream().map(PricingEngine.PricedLine::netAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(r.subtotal(), nets);
        assertEquals(bd("29.01"), r.subtotal());
    }

    @Test
    void taxIsRoundedPerLineOnDiscountedNet() {
        var r = PricingEngine.price(List.of(line(1, 1, "0.70"), line(2, 1, "0.70")),
            List.of(global(1, "PERCENTAGE", "10", null)), VAT);
        // each line net 0.63 -> tax 0.0945 -> 0.09; two lines 0.18 (not 0.19 from a single 1.26 * 0.15)
        assertEquals(bd("0.18"), r.tax());
        assertEquals(r.subtotal().add(r.tax()), r.total());
    }

    @Test
    void offerOfOtherProductDoesNotApply() {
        var r = PricingEngine.price(List.of(line(9, 1, "10.00")), List.of(product(1, "PERCENTAGE", "50", 1L)), VAT);
        assertNull(r.lines().get(0).ownOffer());
        assertEquals(bd("10.00"), r.subtotal());
    }

    @Test
    void emptyCart_noGlobalOffer() {
        var r = PricingEngine.price(List.of(), List.of(global(1, "AMOUNT", "5", null)), VAT);
        assertNull(r.globalOffer());
        assertEquals(0, r.total().signum());
    }

    @Test
    void isCurrent_endDateIncludesWholeDay() {
        Offer o = global(1, "AMOUNT", "1", null);
        assertTrue(o.isCurrent(D));
        assertFalse(o.isCurrent(D.plusDays(1)));
        assertFalse(o.isCurrent(D.minusDays(1)));
    }
}
