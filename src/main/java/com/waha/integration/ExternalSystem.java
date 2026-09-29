package com.waha.integration;

import java.time.Instant;

public record ExternalSystem(
    long id,
    String name,
    String baseUrl,
    String apiKey,
    String username,
    String customerOverride,
    String pushTarget,
    Long ownerOrganizationId,
    boolean enabled,
    Instant lastCategorySyncAt,
    Instant lastProductSyncAt,
    Instant createdAt,
    Instant updatedAt
) {}
