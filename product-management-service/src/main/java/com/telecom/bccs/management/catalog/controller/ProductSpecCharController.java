package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductSpecChar;
import com.telecom.bccs.management.catalog.service.ProductSpecCharService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_SPEC_CHAR. */
@RestController
@RequestMapping("/api/v1/management/product-spec-chars")
public class ProductSpecCharController {

    private final ProductSpecCharService service;

    public ProductSpecCharController(ProductSpecCharService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductSpecChar> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductSpecChar get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
