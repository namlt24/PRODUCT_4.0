package com.telecom.bccs.management.model;

import com.telecom.bccs.management.entity.ProductEntity;

import java.time.Instant;

public record ProductResponse(
        String id,
        String code,
        String name,
        String category,
        String status,
        String description,
        Instant updatedAt
) {
    public static ProductResponse from(ProductEntity e) {
        return new ProductResponse(e.getId(), e.getCode(), e.getName(), e.getCategory(),
                e.getStatus(), e.getDescription(), e.getUpdatedAt());
    }
}
