package com.waha.coupon;

import com.waha.offer.Offer;
import com.waha.offer.OfferClock;
import com.waha.offer.OfferRepository;
import com.waha.offer.OfferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponAdminServiceTest {

    private static final long ORG = 1;

    private CouponAdminRepository repo;
    private OfferRepository offers;
    private CouponAdminService service;

    private static Offer offer(long id, long org, String mode) {
        return new Offer(id, org, "o", Offer.GLOBAL, Offer.PERCENTAGE, BigDecimal.TEN, null,
            LocalDate.now(), LocalDate.now().plusDays(5), true, "ALL", Set.of(), mode);
    }

    private static CouponAdminService.Request req(String code, Long offerId) {
        return new CouponAdminService.Request(code, offerId, null, null, null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        repo = mock(CouponAdminRepository.class);
        offers = mock(OfferRepository.class);
        service = new CouponAdminService(repo, offers, mock(OfferClock.class), mock(PlatformTransactionManager.class));
        when(offers.loadById(10L)).thenReturn(Optional.of(offer(10, ORG, Offer.COUPON)));
    }

    private java.util.Map<String, String> errorsOf(CouponAdminService.Request r, Long existingId, Long existingOffer) {
        return assertThrows(OfferService.ValidationException.class,
            () -> service.validate(r, ORG, existingId, existingOffer, true)).errors();
    }

    @Test
    void validRequestIsAcceptedAndTrimmed() {
        var f = service.validate(req("  WAHA-20 ", 10L), ORG, null, null, null);
        assertEquals("WAHA-20", f.code());
        assertTrue(f.enabled());
    }

    @Test
    void codeRules() {
        assertTrue(errorsOf(req("", 10L), null, null).containsKey("code"));
        assertTrue(errorsOf(req("a", 10L), null, null).containsKey("code"));
        assertTrue(errorsOf(req("bad code!", 10L), null, null).containsKey("code"));
        when(repo.codeTaken(ORG, "waha20", null)).thenReturn(true);
        assertEquals("This code is already used", errorsOf(req("WAHA20", 10L), null, null).get("code"));
    }

    @Test
    void offerMustExistInTheOrganizationAndBeCouponMode() {
        assertTrue(errorsOf(req("OK1", null), null, null).containsKey("offerId"));
        assertTrue(errorsOf(req("OK1", 99L), null, null).containsKey("offerId"));
        when(offers.loadById(11L)).thenReturn(Optional.of(offer(11, 2, Offer.COUPON)));
        assertTrue(errorsOf(req("OK1", 11L), null, null).containsKey("offerId"));
        when(offers.loadById(12L)).thenReturn(Optional.of(offer(12, ORG, Offer.AUTO)));
        assertTrue(errorsOf(req("OK1", 12L), null, null).containsKey("offerId"));
    }

    @Test
    void offerCannotChangeOnceTheCouponWasUsed() {
        when(offers.loadById(13L)).thenReturn(Optional.of(offer(13, ORG, Offer.COUPON)));
        when(repo.hasRedemptions(5L)).thenReturn(true);
        assertTrue(errorsOf(req("OK1", 13L), 5L, 10L).containsKey("offerId"));
        assertDoesNotThrow(() -> service.validate(req("OK1", 10L), ORG, 5L, 10L, true));
    }

    @Test
    void datesLimitsAmountsAndEmployee() {
        var bad = new CouponAdminService.Request("OK1", 10L, null, "2026-12-31T10:00", "2026-12-01T10:00", 0,
            new BigDecimal("-1"), new BigDecimal("1.234"), 77L);
        var e = errorsOf(bad, null, null);
        assertTrue(e.containsKey("expiresAt"));
        assertTrue(e.containsKey("usageLimit"));
        assertTrue(e.containsKey("minimumOrderAmount"));
        assertTrue(e.containsKey("maxDiscountPerOrder"));
        assertTrue(e.containsKey("employeeId"));
        assertTrue(errorsOf(new CouponAdminService.Request("OK1", 10L, null, "tomorrow", null, null, null, null, null), null, null)
            .containsKey("startsAt"));
        when(repo.employeeInOrg(5L, ORG)).thenReturn(true);
        assertDoesNotThrow(() -> service.validate(new CouponAdminService.Request("OK1", 10L, true, "2026-12-01 09:00",
            "2026-12-31T23:59:59", 3, new BigDecimal("10"), new BigDecimal("5.5"), 5L), ORG, null, null, null));
    }

    @Test
    void generateChecksCountAndPrefix() {
        var e = assertThrows(OfferService.ValidationException.class, () -> service.generate(ORG,
            new CouponAdminService.GenerateRequest(10L, 501, "TOO-LONG-PREFIX!", null, null, null, null, null, null), 1L)).errors();
        assertTrue(e.containsKey("count"));
        assertTrue(e.containsKey("prefix"));
    }
}
