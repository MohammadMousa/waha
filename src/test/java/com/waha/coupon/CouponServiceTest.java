package com.waha.coupon;

import com.waha.offer.OfferClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);
    private static final long ORG = 1, STORE = 7;

    private CouponRepository coupons;
    private CouponService service;

    // Optional limits are set by the tests through this small builder.
    private static final class B {
        boolean enabled = true;
        LocalDateTime starts, expires;
        String scope = "ALL";
        Set<Long> stores = Set.of();
        Integer inv, invUser;
        String cap, amt, amtUser, min;
        boolean stack = true;

        Coupon build() {
            return new Coupon(1, ORG, "WAHA20", "Twenty", "GLOBAL", "PERCENTAGE", new BigDecimal("10"),
                cap == null ? null : new BigDecimal(cap), scope, stores, Set.of(), enabled, starts, expires,
                inv, invUser, amt == null ? null : new BigDecimal(amt), amtUser == null ? null : new BigDecimal(amtUser),
                min == null ? null : new BigDecimal(min), stack, null);
        }
    }

    @BeforeEach
    void setUp() {
        coupons = mock(CouponRepository.class);
        OfferClock clock = mock(OfferClock.class);
        when(clock.now()).thenReturn(NOW);
        when(clock.today()).thenReturn(NOW.toLocalDate());
        service = new CouponService(coupons, clock);
        when(coupons.paidAmount(1)).thenReturn(BigDecimal.ZERO);
        when(coupons.paidAmount(1, "u1")).thenReturn(BigDecimal.ZERO);
    }

    private void given(Coupon c) {
        when(coupons.findByCode(ORG, "waha20")).thenReturn(Optional.of(c));
    }

    private String errorOf(String user) {
        return assertThrows(CouponException.class, () -> service.resolve("waha20", STORE, ORG, user)).code;
    }

    @Test
    void validCouponIsResolvedWhateverTheCaseAndSpacing() {
        given(new B().build());
        when(coupons.findByCode(ORG, "waha20")).thenReturn(Optional.of(new B().build()));
        assertEquals("WAHA20", service.resolve("  waHa20 ", STORE, ORG, null).coupon().code());
    }

    @Test
    void unknownCodeIsNotFound() {
        when(coupons.findByCode(ORG, "nope")).thenReturn(Optional.empty());
        var e = assertThrows(CouponException.class, () -> service.resolve("NOPE", STORE, ORG, null));
        assertEquals("COUPON_NOT_FOUND", e.code);
    }

    @Test
    void disabledCouponIsRejected() {
        var b = new B(); b.enabled = false;
        given(b.build());
        assertEquals("COUPON_DISABLED", errorOf(null));
    }

    @Test
    void couponWindowIsEnforced() {
        var b = new B(); b.starts = NOW.plusHours(1);
        given(b.build());
        assertEquals("COUPON_NOT_YET_VALID", errorOf(null));
        b = new B(); b.expires = NOW.minusMinutes(1);
        given(b.build());
        assertEquals("COUPON_EXPIRED", errorOf(null));
    }

    @Test
    void storeScopeIsEnforced() {
        var b = new B(); b.scope = "SOME"; b.stores = Set.of(9L);
        given(b.build());
        assertEquals("COUPON_WRONG_STORE", errorOf(null));
        b.stores = Set.of(STORE);
        given(b.build());
        assertDoesNotThrow(() -> service.resolve("waha20", STORE, ORG, null));
    }

    @Test
    void totalInvoiceLimitCountsPaidRedemptions() {
        var b = new B(); b.inv = 2;
        given(b.build());
        when(coupons.paidInvoices(1)).thenReturn(1L);
        assertDoesNotThrow(() -> service.resolve("waha20", STORE, ORG, null));
        when(coupons.paidInvoices(1)).thenReturn(2L);
        assertEquals("COUPON_USAGE_LIMIT_REACHED", errorOf(null));
    }

    @Test
    void perUserInvoiceLimitNeedsAUsername() {
        var b = new B(); b.invUser = 1;
        given(b.build());
        when(coupons.paidInvoices(1, "u1")).thenReturn(1L);
        assertEquals("COUPON_USER_LIMIT_REACHED", errorOf("u1"));
        assertDoesNotThrow(() -> service.resolve("waha20", STORE, ORG, null));
        when(coupons.paidInvoices(1, "u2")).thenReturn(0L);
        assertDoesNotThrow(() -> service.resolve("waha20", STORE, ORG, "u2"));
    }

    @Test
    void amountLimitCapsTheLastUseAndThenExhausts() {
        var b = new B(); b.amt = "100.00"; b.cap = "30.00";
        given(b.build());
        when(coupons.paidAmount(1)).thenReturn(new BigDecimal("90.00"));
        assertEquals(0, new BigDecimal("10.00").compareTo(service.resolve("waha20", STORE, ORG, null).cap()));
        when(coupons.paidAmount(1)).thenReturn(new BigDecimal("100.00"));
        assertEquals("COUPON_AMOUNT_EXHAUSTED", errorOf(null));
    }

    @Test
    void perUserAmountLimitIsTheSmallestCap() {
        var b = new B(); b.amt = "100.00"; b.amtUser = "20.00"; b.cap = "50.00";
        given(b.build());
        when(coupons.paidAmount(1)).thenReturn(new BigDecimal("10.00"));
        when(coupons.paidAmount(1, "u1")).thenReturn(new BigDecimal("15.00"));
        assertEquals(0, new BigDecimal("5.00").compareTo(service.resolve("waha20", STORE, ORG, "u1").cap()));
        when(coupons.paidAmount(1, "u1")).thenReturn(new BigDecimal("20.00"));
        assertEquals("COUPON_USER_AMOUNT_EXHAUSTED", errorOf("u1"));
    }

    @Test
    void noLimitsMeansNoCap() {
        given(new B().build());
        assertNull(service.resolve("waha20", STORE, ORG, "u1").cap());
    }

    @Test
    void minimumOrderIsCheckedJustBelowAndAtTheThreshold() {
        var b = new B(); b.min = "50.00";
        var c = b.build();
        var e = assertThrows(CouponException.class, () -> service.checkMinimum(c, new BigDecimal("49.99")));
        assertEquals("COUPON_MINIMUM_ORDER_NOT_MET", e.code);
        assertDoesNotThrow(() -> service.checkMinimum(c, new BigDecimal("50.00")));
        assertDoesNotThrow(() -> service.checkMinimum(new B().build(), BigDecimal.ZERO));
    }

    @Test
    void nonStackingCouponIsRefusedWhenAnOfferAlreadyApplies() {
        var b = new B(); b.stack = false;
        var c = b.build();
        var e = assertThrows(CouponException.class, () -> service.checkStacking(c, new BigDecimal("0.50")));
        assertEquals("COUPON_NOT_COMBINABLE", e.code);
        assertDoesNotThrow(() -> service.checkStacking(c, BigDecimal.ZERO));
        assertDoesNotThrow(() -> service.checkStacking(new B().build(), new BigDecimal("5")));
    }
}
