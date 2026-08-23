package com.waha.product.dto;

import java.time.Instant;
import java.util.List;

// syncedAt is server time at the moment this call ran - the client should
// store THIS as its next "since" cursor, not the max updatedAt among the
// returned rows. Using the row data for the cursor risks missing a product
// that changes in the same instant as the query runs; the server's own
// clock is the only safe watermark.
public record ProductSyncResponse(Instant syncedAt, List<ProductSyncItem> products) {}
