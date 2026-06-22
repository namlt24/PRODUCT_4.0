package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductSpecCharUse;
import com.telecom.bccs.management.catalog.repository.ProductSpecCharUseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_SPEC_CHAR_USE (chỉ tra cứu). */
@Service
public class ProductSpecCharUseService {

    private final ProductSpecCharUseRepository repository;

    public ProductSpecCharUseService(ProductSpecCharUseRepository repository) {
        this.repository = repository;
    }

    public List<ProductSpecCharUse> findAll() {
        return repository.findAll();
    }

    public ProductSpecCharUse findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductSpecCharUse not found: " + id));
    }
}
