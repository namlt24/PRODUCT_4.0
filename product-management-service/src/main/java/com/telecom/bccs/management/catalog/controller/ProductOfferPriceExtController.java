package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductOfferPriceExt;
import com.telecom.bccs.management.catalog.service.ProductOfferPriceExtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_OFFER_PRICE_EXT. */
@RestController
@RequestMapping("/api/v1/management/product-offer-price-exts")
public class ProductOfferPriceExtController {

    private final ProductOfferPriceExtService service;

    public ProductOfferPriceExtController(ProductOfferPriceExtService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductOfferPriceExt> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductOfferPriceExt get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
