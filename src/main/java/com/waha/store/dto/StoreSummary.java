package com.waha.store.dto;

import com.fasterxml.jackson.databind.JsonNode;

// displayName is a JsonNode, not a String - Jackson serializes that as a
// real nested JSON object in the response ({"ar":"...","en":"..."}), not a
// re-escaped string. The backend never picks a language here; the whole
// blob goes out as-is, same principle as stores.display_name itself.
public record StoreSummary(long id, String name, JsonNode displayName, String currency) {}
