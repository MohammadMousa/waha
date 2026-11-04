package com.waha.offer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfferPricingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static Offer global(long id, String mode) {
        return new Offer(id, 1, "o" + id, Offer.GLOBAL, Offer.PERCENTAGE, new BigDecimal("10"), null,
            TODAY.minusDays(1), TODAY.plusDays(1), true, "ALL", Set.of(), mode);
    }

    private OfferPricing pricing(List<Offer> stored) {
        OfferRepository repo = mock(OfferRepository.class);
        OfferClock clock = mock(OfferClock.class);
        when(clock.today()).thenReturn(TODAY);
        when(repo.loadForStore(1L)).thenReturn(stored);
        return new OfferPricing(repo, clock);
    }

    private static List<PricingEngine.Line> cart() {
        return List.of(new PricingEngine.Line(1, 2, new BigDecimal("50.00")));
    }

    @Test
    void couponOfferIsNeverAppliedAutomatically() {
        var result = pricing(List.of(global(1, Offer.COUPON))).price(1, cart(), new BigDecimal("0.15"));
        assertEquals(0, result.discountAmount().signum());
        assertEquals(new BigDecimal("100.00"), result.subtotal());
    }

    @Test
    void couponOfferIsNotShownAsABadge() {
        assertEquals(List.of(), pricing(List.of(global(1, Offer.COUPON))).currentOffers(1));
    }

    @Test
    void automaticOffersStillApplyNextToACouponOffer() {
        var result = pricing(List.of(global(1, Offer.COUPON), global(2, Offer.AUTO))).price(1, cart(), new BigDecimal("0.15"));
        assertEquals(new BigDecimal("10.00"), result.discountAmount());
        assertEquals(2L, result.globalOffer().id());
    }

    @Test
    void offersBuiltWithoutAnActivationModeAreAutomatic() {
        Offer legacy = new Offer(3, 1, "legacy", Offer.GLOBAL, Offer.PERCENTAGE, new BigDecimal("10"), null,
            TODAY, TODAY, true, "ALL", Set.of());
        assertEquals(Offer.AUTO, legacy.activationMode());
        assertEquals(1, pricing(List.of(legacy)).currentOffers(1).size());
    }
}
