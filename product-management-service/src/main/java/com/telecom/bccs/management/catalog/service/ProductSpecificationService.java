package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductSpecification;
import com.telecom.bccs.management.catalog.repository.ProductSpecificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_SPECIFICATION (chỉ tra cứu). */
@Service
public class ProductSpecificationService {

    private final ProductSpecificationRepository repository;

    public ProductSpecificationService(ProductSpecificationRepository repository) {
        this.repository = repository;
    }

    public List<ProductSpecification> findAll() {
        return repository.findAll();
    }

    public ProductSpecification findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductSpecification not found: " + id));
    }
}
