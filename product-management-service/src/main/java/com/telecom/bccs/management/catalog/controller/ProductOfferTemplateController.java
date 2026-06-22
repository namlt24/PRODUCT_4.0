package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductOfferTemplate;
import com.telecom.bccs.management.catalog.service.ProductOfferTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_OFFER_TEMPLATE. */
@RestController
@RequestMapping("/api/v1/management/product-offer-templates")
public class ProductOfferTemplateController {

    private final ProductOfferTemplateService service;

    public ProductOfferTemplateController(ProductOfferTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductOfferTemplate> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductOfferTemplate get(@PathVariable Long id) {
        return service.findById(id);
    }
}
