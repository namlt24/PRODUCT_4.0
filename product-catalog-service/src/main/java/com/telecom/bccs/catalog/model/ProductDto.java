package com.telecom.bccs.catalog.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * Cacheable product view. Implements Serializable because instances are stored in Redis (L2)
 * via JSON serialization and may also be held in Caffeine (L1).
 */
public record ProductDto(
        String id,
        String code,
        String name,
        String category,
        String status,
        String description,
        Instant updatedAt
) implements Serializable {
}
