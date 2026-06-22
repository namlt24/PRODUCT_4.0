package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductOfferCharUse;
import com.telecom.bccs.management.catalog.service.ProductOfferCharUseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_OFFER_CHAR_USE. */
@RestController
@RequestMapping("/api/v1/management/product-offer-char-uses")
public class ProductOfferCharUseController {

    private final ProductOfferCharUseService service;

    public ProductOfferCharUseController(ProductOfferCharUseService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductOfferCharUse> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductOfferCharUse get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
