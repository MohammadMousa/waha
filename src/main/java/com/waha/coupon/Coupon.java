package com.waha.coupon;

import com.waha.offer.Offer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

// A standalone coupon with its own discount rule and limits (null limit = unlimited). startsAt and expiresAt are
// local date-times in the offers time zone (Riyadh); either may be null. productIds is empty for GLOBAL coupons;
// storeIds is empty when the coupon is valid in every store.
public record Coupon(long id, long organizationId, String code, String name, String type, String discountMode,
                     BigDecimal discountValue, BigDecimal maxDiscountPerOrder, String storeScope,
                     Set<Long> storeIds, Set<Long> productIds, boolean enabled,
                     LocalDateTime startsAt, LocalDateTime expiresAt,
                     Integer maxInvoices, Integer maxInvoicesPerUser, BigDecimal maxAmount, BigDecimal maxAmountPerUser,
                     BigDecimal minimumOrderAmount, boolean allowStackingWithOffers, Long employeeId) {

    private static final LocalDate FROM = LocalDate.of(2000, 1, 1);
    private static final LocalDate TO = LocalDate.of(2999, 12, 31);

    // Codes are compared trimmed and case-insensitively.
    public static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean coversStore(long storeId) {
        return !"SOME".equals(storeScope) || storeIds.contains(storeId);
    }

    // The discount rule in the form the pricing engine takes. Dates and the per-order cap are handled elsewhere.
    public Offer rule() {
        return new Offer(id, organizationId, name != null && !name.isBlank() ? name : code, type, discountMode,
            discountValue, null, FROM, TO, true, "ALL", productIds);
    }
}
