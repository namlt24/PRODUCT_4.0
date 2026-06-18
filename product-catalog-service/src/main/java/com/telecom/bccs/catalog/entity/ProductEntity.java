package com.telecom.bccs.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "product")
public class ProductEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "code", length = 64, nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ProductEntity() {
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public String getDescription() { return description; }
    public Instant getUpdatedAt() { return updatedAt; }
}
