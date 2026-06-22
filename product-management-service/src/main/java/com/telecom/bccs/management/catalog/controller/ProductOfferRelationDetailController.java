package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductOfferRelationDetail;
import com.telecom.bccs.management.catalog.service.ProductOfferRelationDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_OFFER_RELATION_DETAIL. */
@RestController
@RequestMapping("/api/v1/management/product-offer-relation-details")
public class ProductOfferRelationDetailController {

    private final ProductOfferRelationDetailService service;

    public ProductOfferRelationDetailController(ProductOfferRelationDetailService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductOfferRelationDetail> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductOfferRelationDetail get(@PathVariable Long id) {
        return service.findById(id);
    }
}
