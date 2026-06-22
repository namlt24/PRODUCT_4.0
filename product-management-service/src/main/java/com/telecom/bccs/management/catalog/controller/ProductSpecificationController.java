package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductSpecification;
import com.telecom.bccs.management.catalog.service.ProductSpecificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_SPECIFICATION. */
@RestController
@RequestMapping("/api/v1/management/product-specifications")
public class ProductSpecificationController {

    private final ProductSpecificationService service;

    public ProductSpecificationController(ProductSpecificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductSpecification> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductSpecification get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
