package com.waha.integration;

import java.time.Instant;

public record ExternalSystem(
    long id,
    String name,
    String baseUrl,
    String apiKey,
    String username,
    String customerOverride,
    Long ownerStoreId,
    boolean enabled,
    Instant lastCategorySyncAt,
    Instant lastProductSyncAt,
    Instant createdAt,
    Instant updatedAt
) {}
