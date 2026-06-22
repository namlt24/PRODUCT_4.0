package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductOfferRelation;
import com.telecom.bccs.management.catalog.service.ProductOfferRelationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_OFFER_RELATION. */
@RestController
@RequestMapping("/api/v1/management/product-offer-relations")
public class ProductOfferRelationController {

    private final ProductOfferRelationService service;

    public ProductOfferRelationController(ProductOfferRelationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductOfferRelation> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductOfferRelation get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
