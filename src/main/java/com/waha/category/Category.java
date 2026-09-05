package com.waha.category;

import com.fasterxml.jackson.databind.JsonNode;

// companyId: which company this category belongs to.
// name is the raw {"ar": "...", "en": "..."} blob; locale selection is client-side.
public record Category(long id, long companyId, JsonNode name, boolean publicVisible, boolean active, int sortOrder, Long imageResourceId) {}
