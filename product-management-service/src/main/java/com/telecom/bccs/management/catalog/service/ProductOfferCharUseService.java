package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductOfferCharUse;
import com.telecom.bccs.management.catalog.repository.ProductOfferCharUseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_OFFER_CHAR_USE (chỉ tra cứu). */
@Service
public class ProductOfferCharUseService {

    private final ProductOfferCharUseRepository repository;

    public ProductOfferCharUseService(ProductOfferCharUseRepository repository) {
        this.repository = repository;
    }

    public List<ProductOfferCharUse> findAll() {
        return repository.findAll();
    }

    public ProductOfferCharUse findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductOfferCharUse not found: " + id));
    }
}
