package com.waha.integration;

import java.time.Instant;

public record SyncQueueItem(
    long id,
    long systemId,
    String entityType,
    String entityId,
    String operation,
    String payload,
    String status,
    int attempts,
    String lastError,
    Long storeId,
    Instant createdAt,
    Instant updatedAt
) {}
