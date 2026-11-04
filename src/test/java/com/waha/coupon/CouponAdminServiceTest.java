package com.waha.coupon;

import com.waha.offer.OfferClock;
import com.waha.offer.OfferRepository;
import com.waha.offer.OfferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponAdminServiceTest {

    private static final long ORG = 1;

    private CouponAdminRepository repo;
    private OfferRepository offers;
    private CouponAdminService service;

    // A valid global 10% coupon; tests change one thing at a time with the with* helpers.
    private static CouponAdminService.Request req(String code) {
        return new CouponAdminService.Request(code, null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CouponAdminService.Request limits(Integer inv, Integer invUser, String amt, String amtUser, String min) {
        return new CouponAdminService.Request("OK1", null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, null, null, null,
            null, null, null, inv, invUser, amt == null ? null : new BigDecimal(amt),
            amtUser == null ? null : new BigDecimal(amtUser), min == null ? null : new BigDecimal(min), null, null,
            null, null, null);
    }

    @BeforeEach
    void setUp() {
        repo = mock(CouponAdminRepository.class);
        offers = mock(OfferRepository.class);
        service = new CouponAdminService(repo, offers, mock(OfferClock.class), mock(PlatformTransactionManager.class));
    }

    private Map<String, String> errorsOf(CouponAdminService.Request r, Long existingId, String existingType) {
        return assertThrows(OfferService.ValidationException.class,
            () -> service.validate(r, ORG, existingId, existingType, true)).errors();
    }

    @Test
    void validRequestIsAcceptedAndTrimmed() {
        var f = service.validate(req("  WAHA-20 "), ORG, null, null, null);
        assertEquals("WAHA-20", f.code());
        assertTrue(f.enabled());
        assertTrue(f.allowStackingWithOffers());
        assertNull(f.maxInvoices());
        assertEquals("ALL", f.storeScope());
    }

    @Test
    void codeRules() {
        assertTrue(errorsOf(req(""), null, null).containsKey("code"));
        assertTrue(errorsOf(req("a"), null, null).containsKey("code"));
        assertTrue(errorsOf(req("bad code!"), null, null).containsKey("code"));
        when(repo.codeTaken(ORG, "waha20", null)).thenReturn(true);
        assertEquals("This code is already used", errorsOf(req("WAHA20"), null, null).get("code"));
    }

    @Test
    void discountRuleIsChecked() {
        var bad = new CouponAdminService.Request("OK1", null, "WRONG", "NOPE", new BigDecimal("0"), null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
        var e = errorsOf(bad, null, null);
        assertTrue(e.containsKey("type"));
        assertTrue(e.containsKey("discountMode"));
        assertTrue(e.containsKey("discountValue"));
        var over = new CouponAdminService.Request("OK1", null, "GLOBAL", "PERCENTAGE", new BigDecimal("101"), null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(errorsOf(over, null, null).containsKey("discountValue"));
    }

    @Test
    void productCouponNeedsProductsOfTheOrganization() {
        var noProducts = new CouponAdminService.Request("OK1", null, "PRODUCT", "AMOUNT", BigDecimal.ONE, null, null, null,
            List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(errorsOf(noProducts, null, null).containsKey("productIds"));
        var withProducts = new CouponAdminService.Request("OK1", null, "PRODUCT", "AMOUNT", BigDecimal.ONE, null, null, null,
            List.of(5L, 6L), null, null, null, null, null, null, null, null, null, null, null, null, null);
        when(offers.countProductsOf(ORG, List.of(5L, 6L))).thenReturn(1);
        assertTrue(errorsOf(withProducts, null, null).containsKey("productIds"));
        when(offers.countProductsOf(ORG, List.of(5L, 6L))).thenReturn(2);
        assertEquals(List.of(5L, 6L), service.validate(withProducts, ORG, null, null, null).productIds());
    }

    @Test
    void storeScopeSomeNeedsStores() {
        var some = new CouponAdminService.Request("OK1", null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, "SOME", List.of(),
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(errorsOf(some, null, null).containsKey("storeIds"));
    }

    @Test
    void typeCannotChangeWhenEditing() {
        assertTrue(errorsOf(new CouponAdminService.Request("OK1", null, "PRODUCT", "AMOUNT", BigDecimal.ONE, null, null, null,
            List.of(1L), null, null, null, null, null, null, null, null, null, null, null, null, null), 5L, "GLOBAL").containsKey("type"));
    }

    @Test
    void limitsMustBePositiveAndMoneyHasTwoDecimals() {
        var e = errorsOf(limits(0, 0, "-1", "1.234", "0"), null, null);
        assertTrue(e.containsKey("maxInvoices"));
        assertTrue(e.containsKey("maxInvoicesPerUser"));
        assertTrue(e.containsKey("maxAmount"));
        assertTrue(e.containsKey("maxAmountPerUser"));
        assertTrue(e.containsKey("minimumOrderAmount"));
        var f = service.validate(limits(5, 2, "100", "20.5", "10"), ORG, null, null, null);
        assertEquals(5, f.maxInvoices());
        assertEquals(new BigDecimal("20.50"), f.maxAmountPerUser());
    }

    @Test
    void datesAndEmployee() {
        var bad = new CouponAdminService.Request("OK1", null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, null, null, null,
            null, "2026-12-31T10:00", "2026-12-01T10:00", null, null, null, null, null, null, 77L, null, null, null);
        var e = errorsOf(bad, null, null);
        assertTrue(e.containsKey("expiresAt"));
        assertTrue(e.containsKey("employeeId"));
        var notDate = new CouponAdminService.Request("OK1", null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, null, null, null,
            null, "tomorrow", null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(errorsOf(notDate, null, null).containsKey("startsAt"));
        when(repo.employeeInOrg(5L, ORG)).thenReturn(true);
        var ok = new CouponAdminService.Request("OK1", null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, null, null, null,
            true, "2026-12-01 09:00", "2026-12-31T23:59:59", null, null, null, null, null, null, 5L, null, null, null);
        assertDoesNotThrow(() -> service.validate(ok, ORG, null, null, null));
    }

    @Test
    void batchChecksCountAndPrefixOrCodes() {
        var many = new CouponAdminService.Request(null, null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, 501, "TOO-LONG-PREFIX!", null);
        var e = assertThrows(OfferService.ValidationException.class, () -> service.createMany(ORG, many, 1L)).errors();
        assertTrue(e.containsKey("count"));
        assertTrue(e.containsKey("prefix"));
    }

    @Test
    void batchListOfCodesIsValidatedAsAWhole() {
        when(repo.codeTaken(anyLong(), org.mockito.ArgumentMatchers.eq("taken"), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        assertTrue(batchCodes("A1B, a1b").containsKey("codes"));
        assertTrue(batchCodes("GOOD1, bad code!").containsKey("codes"));
        assertTrue(batchCodes("GOOD1, TAKEN").containsKey("codes"));
        assertTrue(batchCodes(" , ").containsKey("codes"));
    }

    private Map<String, String> batchCodes(String codes) {
        var r = new CouponAdminService.Request(null, null, "GLOBAL", "PERCENTAGE", BigDecimal.TEN, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, codes);
        return assertThrows(OfferService.ValidationException.class, () -> service.createMany(ORG, r, 1L)).errors();
    }
}
