package com.waha.integration;

import java.time.Instant;

public record ExternalMapping(
    long id,
    long systemId,
    String entityType,
    String localId,
    String externalId,
    Long storeId,
    Instant createdAt,
    Instant updatedAt
) {}
