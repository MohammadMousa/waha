package com.waha.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

// name/description: bilingual JSON {"ar":"...","en":"..."}.
// imageResourceId included so the client detects avatar changes and
// invalidates its local resource cache for that product.
public record ProductSyncItem(long id, String barcode, JsonNode name, JsonNode description, BigDecimal price, boolean active, Long imageResourceId, Instant updatedAt) {}
