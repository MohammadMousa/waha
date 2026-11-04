package com.waha.coupon;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// startsAt and expiresAt are local date-times in the offers time zone (Riyadh); either may be null.
public record Coupon(long id, long organizationId, long offerId, String code, boolean enabled,
                     LocalDateTime startsAt, LocalDateTime expiresAt, Integer usageLimit,
                     BigDecimal minimumOrderAmount, BigDecimal maxDiscountPerOrder, Long employeeId) {

    // Codes are compared trimmed and case-insensitively.
    public static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
