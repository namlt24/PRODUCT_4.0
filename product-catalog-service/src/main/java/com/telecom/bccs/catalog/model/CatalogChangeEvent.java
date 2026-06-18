package com.telecom.bccs.catalog.model;

import java.io.Serializable;

/**
 * Event consumed from Kafka topic {@code catalog.changes}, published by
 * product-management-service whenever a product/offer is created, updated or deleted.
 * Drives targeted cache invalidation in this service.
 */
public record CatalogChangeEvent(
        String entityType,   // PRODUCT | OFFER
        String entityId,
        String changeType,   // CREATED | UPDATED | DELETED
        long timestamp
) implements Serializable {
}
