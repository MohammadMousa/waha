package com.waha.offer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

// An offer as the pricing engine sees it. productIds is empty for GLOBAL offers.
public record Offer(long id, long organizationId, String name, String type, String discountMode,
                    BigDecimal discountValue, BigDecimal maxDiscountAmount,
                    LocalDate startDate, LocalDate endDate, boolean enabled,
                    String storeScope, Set<Long> productIds) {

    public static final String GLOBAL = "GLOBAL";
    public static final String PRODUCT = "PRODUCT";
    public static final String AMOUNT = "AMOUNT";
    public static final String PERCENTAGE = "PERCENTAGE";

    public boolean isGlobal() { return GLOBAL.equals(type); }

    // Valid on the given store-local date (end date counts for the whole day).
    public boolean isCurrent(LocalDate today) {
        return enabled && !today.isBefore(startDate) && !today.isAfter(endDate);
    }
}
