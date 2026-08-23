package com.waha.invoice;

// Per-store receipt branding. All fields nullable except storeId — a store
// can exist in receipt_info with partial data (only name filled in, address
// added later) without breaking invoice generation. The renderer uses
// whatever is available and skips what's null.
public record ReceiptInfo(
    long storeId,
    String nameAr,
    String nameEn,
    String addressText,
    String vatNumber,
    String crNumber,
    Long logoResourceId
) {}
