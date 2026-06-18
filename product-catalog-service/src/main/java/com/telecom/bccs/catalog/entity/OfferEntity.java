package com.telecom.bccs.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "offer")
public class OfferEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "product_id", length = 64, nullable = false)
    private String productId;

    @Column(name = "code", length = 64, nullable = false)
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "price", precision = 18, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "billing_cycle", length = 16)
    private String billingCycle;

    @Column(name = "status", length = 16)
    private String status;

    protected OfferEntity() {
    }

    public String getId() { return id; }
    public String getProductId() { return productId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public String getBillingCycle() { return billingCycle; }
    public String getStatus() { return status; }
}
