package com.waha.category;

import com.fasterxml.jackson.databind.JsonNode;

// scope_store_id NULL = globally visible category (all stores inherit it).
// name is the raw {"ar": "...", "en": "..."} blob; locale selection is
// client-side, same as everywhere else.
public record Category(long id, Long scopeStoreId, JsonNode name, boolean publicVisible, boolean active, int sortOrder) {}
