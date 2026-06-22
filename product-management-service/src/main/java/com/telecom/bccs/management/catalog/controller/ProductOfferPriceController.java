package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductOfferPrice;
import com.telecom.bccs.management.catalog.service.ProductOfferPriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_OFFER_PRICE. */
@RestController
@RequestMapping("/api/v1/management/product-offer-prices")
public class ProductOfferPriceController {

    private final ProductOfferPriceService service;

    public ProductOfferPriceController(ProductOfferPriceService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductOfferPrice> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductOfferPrice get(@PathVariable Long id) {
        return service.findById(id);
    }
}
