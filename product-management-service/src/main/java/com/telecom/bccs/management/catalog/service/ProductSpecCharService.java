package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductSpecChar;
import com.telecom.bccs.management.catalog.repository.ProductSpecCharRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_SPEC_CHAR (chỉ tra cứu). */
@Service
public class ProductSpecCharService {

    private final ProductSpecCharRepository repository;

    public ProductSpecCharService(ProductSpecCharRepository repository) {
        this.repository = repository;
    }

    public List<ProductSpecChar> findAll() {
        return repository.findAll();
    }

    public ProductSpecChar findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductSpecChar not found: " + id));
    }
}
