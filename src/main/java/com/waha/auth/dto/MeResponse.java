package com.waha.auth.dto;

import java.util.Map;
import java.util.Set;

public record MeResponse(long userId, String username, Long storeId, Long defaultStoreId, String mode, Map<String, String> properties, String roleName, Set<String> permissions) {}
