package com.telecom.bccs.management.model;

import java.io.Serializable;

/** Event published to {@code catalog.changes} so the catalog service can invalidate its cache. */
public record CatalogChangeEvent(
        String entityType,   // PRODUCT | OFFER
        String entityId,
        String changeType,   // CREATED | UPDATED | DELETED
        long timestamp
) implements Serializable {
}
