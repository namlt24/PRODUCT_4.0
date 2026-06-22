package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductSpecCharValue;
import com.telecom.bccs.management.catalog.service.ProductSpecCharValueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_SPEC_CHAR_VALUE. */
@RestController
@RequestMapping("/api/v1/management/product-spec-char-values")
public class ProductSpecCharValueController {

    private final ProductSpecCharValueService service;

    public ProductSpecCharValueController(ProductSpecCharValueService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductSpecCharValue> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductSpecCharValue get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
