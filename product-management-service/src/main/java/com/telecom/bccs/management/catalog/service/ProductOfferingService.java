package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductOffering;
import com.telecom.bccs.management.catalog.repository.ProductOfferingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_OFFERING (chỉ tra cứu). */
@Service
public class ProductOfferingService {

    private final ProductOfferingRepository repository;

    public ProductOfferingService(ProductOfferingRepository repository) {
        this.repository = repository;
    }

    public List<ProductOffering> findAll() {
        return repository.findAll();
    }

    public ProductOffering findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductOffering not found: " + id));
    }
}
