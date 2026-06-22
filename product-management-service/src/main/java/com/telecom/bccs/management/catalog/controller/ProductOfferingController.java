package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductOffering;
import com.telecom.bccs.management.catalog.service.ProductOfferingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_OFFERING. */
@RestController
@RequestMapping("/api/v1/management/product-offerings")
public class ProductOfferingController {

    private final ProductOfferingService service;

    public ProductOfferingController(ProductOfferingService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductOffering> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductOffering get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
