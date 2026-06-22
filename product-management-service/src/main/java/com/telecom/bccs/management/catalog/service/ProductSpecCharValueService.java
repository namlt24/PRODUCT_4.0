package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductSpecCharValue;
import com.telecom.bccs.management.catalog.repository.ProductSpecCharValueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_SPEC_CHAR_VALUE (chỉ tra cứu). */
@Service
public class ProductSpecCharValueService {

    private final ProductSpecCharValueRepository repository;

    public ProductSpecCharValueService(ProductSpecCharValueRepository repository) {
        this.repository = repository;
    }

    public List<ProductSpecCharValue> findAll() {
        return repository.findAll();
    }

    public ProductSpecCharValue findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductSpecCharValue not found: " + id));
    }
}
