package com.waha.auth.dto;

import java.util.Map;
import java.util.Set;

public record MeResponse(long userId, String username, Long storeId, Long defaultStoreId, String mode, Map<String, String> properties, Set<String> permissions) {}
