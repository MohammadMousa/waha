package com.waha.invoice;

// Per-organization receipt branding. All fields nullable except organizationId.
// The renderer uses whatever is available and skips what's null.
public record ReceiptInfo(
    long organizationId,
    String nameAr,
    String nameEn,
    String addressText,
    String vatNumber,
    String crNumber,
    Long logoResourceId,
    String unpaidInvoiceTitle,
    String paidInvoiceTitle
) {}
