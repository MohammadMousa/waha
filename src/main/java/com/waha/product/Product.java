package com.waha.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

// name: {"ar":"...","en":"..."} — bilingual, locale selection client-side.
// description: {"ar":"...","en":"..."} — optional long text for detail screen.
// scopeStoreId: null = GLOBAL (admissible everywhere).
// publicListed: controls browse-list visibility (false = scan-only).
// categoryId: soft FK to categories.id; null if uncategorized.
// imageResourceId: avatar resource; null if no image uploaded yet.
// tags: free-form labels; populated only on detail responses (not browse/search lists).
public record Product(long id, String barcode, JsonNode name, JsonNode description, BigDecimal price, boolean active, Long scopeStoreId, boolean publicListed, Long categoryId, Long imageResourceId, List<String> tags) {

    public Product(long id, String barcode, JsonNode name, JsonNode description, BigDecimal price, boolean active, Long scopeStoreId, boolean publicListed, Long categoryId, Long imageResourceId) {
        this(id, barcode, name, description, price, active, scopeStoreId, publicListed, categoryId, imageResourceId, List.of());
    }
}
