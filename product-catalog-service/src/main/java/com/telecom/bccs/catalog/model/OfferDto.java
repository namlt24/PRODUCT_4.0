package com.telecom.bccs.catalog.model;

import java.io.Serializable;
import java.math.BigDecimal;

public record OfferDto(
        String id,
        String productId,
        String code,
        String name,
        BigDecimal price,
        String currency,
        String billingCycle,
        String status
) implements Serializable {
}
