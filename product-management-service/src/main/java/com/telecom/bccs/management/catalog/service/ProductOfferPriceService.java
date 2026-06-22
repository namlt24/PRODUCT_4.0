package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductOfferPrice;
import com.telecom.bccs.management.catalog.repository.ProductOfferPriceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_OFFER_PRICE (chỉ tra cứu). */
@Service
public class ProductOfferPriceService {

    private final ProductOfferPriceRepository repository;

    public ProductOfferPriceService(ProductOfferPriceRepository repository) {
        this.repository = repository;
    }

    public List<ProductOfferPrice> findAll() {
        return repository.findAll();
    }

    public ProductOfferPrice findById(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductOfferPrice not found: " + id));
    }
}
