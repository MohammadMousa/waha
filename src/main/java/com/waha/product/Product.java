package com.waha.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

// name: {"ar":"...","en":"..."} — bilingual, locale selection client-side.
// description: {"ar":"...","en":"..."} — optional long text for detail screen.
// scopeStoreId: null = GLOBAL (admissible everywhere).
// publicListed: controls browse-list visibility (false = scan-only).
// categoryId: soft FK to categories.id; null if uncategorized.
// imageResourceId: avatar resource; null if no image uploaded yet.
public record Product(long id, String barcode, JsonNode name, JsonNode description, BigDecimal price, boolean active, Long scopeStoreId, boolean publicListed, Long categoryId, Long imageResourceId) {}
