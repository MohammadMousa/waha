package com.waha.coupon;

import com.waha.offer.Offer;
import com.waha.offer.OfferClock;
import com.waha.offer.OfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);
    private static final long ORG = 1, STORE = 7;

    private CouponRepository coupons;
    private OfferRepository offers;
    private CouponService service;

    private static Offer offer(String mode, boolean enabled, LocalDate start, LocalDate end, long org) {
        return new Offer(10, org, "Offer", Offer.GLOBAL, Offer.PERCENTAGE, new BigDecimal("10"), null,
            start, end, enabled, "ALL", Set.of(), mode);
    }

    private static Coupon coupon(boolean enabled, LocalDateTime starts, LocalDateTime expires, Integer limit, String min) {
        return new Coupon(1, ORG, 10, "WAHA20", enabled, starts, expires, limit,
            min == null ? null : new BigDecimal(min), null, null);
    }

    @BeforeEach
    void setUp() {
        coupons = mock(CouponRepository.class);
        offers = mock(OfferRepository.class);
        OfferClock clock = mock(OfferClock.class);
        when(clock.now()).thenReturn(NOW);
        when(clock.today()).thenReturn(NOW.toLocalDate());
        service = new CouponService(coupons, offers, clock);
        when(offers.offerCoversStore(org.mockito.ArgumentMatchers.any(), anyLong())).thenReturn(true);
    }

    private void given(Coupon c, Offer o) {
        when(coupons.findByCode(ORG, "waha20")).thenReturn(Optional.of(c));
        when(offers.loadById(c.offerId())).thenReturn(Optional.ofNullable(o));
    }

    private String errorOf(String code) {
        return assertThrows(CouponException.class, () -> service.resolve(code, STORE, ORG)).code;
    }

    private static Offer validOffer() {
        return offer(Offer.COUPON, true, NOW.toLocalDate().minusDays(1), NOW.toLocalDate().plusDays(1), ORG);
    }

    @Test
    void validCouponIsResolvedWhateverTheCaseAndSpacing() {
        given(coupon(true, null, null, null, null), validOffer());
        assertEquals("WAHA20", service.resolve("  waHa20 ", STORE, ORG).coupon().code());
    }

    @Test
    void unknownCodeIsNotFound() {
        when(coupons.findByCode(ORG, "nope")).thenReturn(Optional.empty());
        assertEquals("COUPON_NOT_FOUND", errorOf("NOPE"));
    }

    @Test
    void disabledCouponIsRejected() {
        given(coupon(false, null, null, null, null), validOffer());
        assertEquals("COUPON_DISABLED", errorOf("waha20"));
    }

    @Test
    void couponWindowIsEnforced() {
        given(coupon(true, NOW.plusHours(1), null, null, null), validOffer());
        assertEquals("COUPON_NOT_YET_VALID", errorOf("waha20"));
        given(coupon(true, null, NOW.minusMinutes(1), null, null), validOffer());
        assertEquals("COUPON_EXPIRED", errorOf("waha20"));
    }

    @Test
    void offerProblemsMakeTheCouponUnavailable() {
        var day = NOW.toLocalDate();
        given(coupon(true, null, null, null, null), null);
        assertEquals("COUPON_OFFER_UNAVAILABLE", errorOf("waha20"));
        given(coupon(true, null, null, null, null), offer(Offer.COUPON, false, day.minusDays(1), day.plusDays(1), ORG));
        assertEquals("COUPON_OFFER_UNAVAILABLE", errorOf("waha20"));
        given(coupon(true, null, null, null, null), offer(Offer.COUPON, true, day.minusDays(9), day.minusDays(1), ORG));
        assertEquals("COUPON_OFFER_UNAVAILABLE", errorOf("waha20"));
        given(coupon(true, null, null, null, null), offer(Offer.AUTO, true, day.minusDays(1), day.plusDays(1), ORG));
        assertEquals("COUPON_OFFER_UNAVAILABLE", errorOf("waha20"));
        given(coupon(true, null, null, null, null), offer(Offer.COUPON, true, day.minusDays(1), day.plusDays(1), 2));
        assertEquals("COUPON_OFFER_UNAVAILABLE", errorOf("waha20"));
    }

    @Test
    void offerOutsideTheStoreScopeIsWrongStore() {
        given(coupon(true, null, null, null, null), validOffer());
        when(offers.offerCoversStore(org.mockito.ArgumentMatchers.any(), anyLong())).thenReturn(false);
        assertEquals("COUPON_WRONG_STORE", errorOf("waha20"));
    }

    @Test
    void usageLimitCountsPaidRedemptions() {
        given(coupon(true, null, null, 2, null), validOffer());
        when(coupons.paidUsage(1)).thenReturn(1L);
        assertDoesNotThrow(() -> service.resolve("waha20", STORE, ORG));
        when(coupons.paidUsage(1)).thenReturn(2L);
        assertEquals("COUPON_USAGE_LIMIT_REACHED", errorOf("waha20"));
    }

    @Test
    void minimumOrderIsCheckedJustBelowAndAtTheThreshold() {
        var c = coupon(true, null, null, null, "50.00");
        var e = assertThrows(CouponException.class, () -> service.checkMinimum(c, new BigDecimal("49.99")));
        assertEquals("COUPON_MINIMUM_ORDER_NOT_MET", e.code);
        assertDoesNotThrow(() -> service.checkMinimum(c, new BigDecimal("50.00")));
        assertDoesNotThrow(() -> service.checkMinimum(coupon(true, null, null, null, null), BigDecimal.ZERO));
    }
}
