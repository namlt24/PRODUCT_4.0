package com.telecom.bccs.management.catalog.controller;

import com.telecom.bccs.management.catalog.entity.ProductSpecCharUse;
import com.telecom.bccs.management.catalog.service.ProductSpecCharUseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST chỉ đọc cho PRODUCT_SPEC_CHAR_USE. */
@RestController
@RequestMapping("/api/v1/management/product-spec-char-uses")
public class ProductSpecCharUseController {

    private final ProductSpecCharUseService service;

    public ProductSpecCharUseController(ProductSpecCharUseService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductSpecCharUse> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ProductSpecCharUse get(@PathVariable Integer id) {
        return service.findById(id);
    }
}
