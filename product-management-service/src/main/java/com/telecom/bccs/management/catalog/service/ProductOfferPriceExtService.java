package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductOfferPriceExt;
import com.telecom.bccs.management.catalog.repository.ProductOfferPriceExtRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_OFFER_PRICE_EXT (chỉ tra cứu). */
@Service
public class ProductOfferPriceExtService {

    private final ProductOfferPriceExtRepository repository;

    public ProductOfferPriceExtService(ProductOfferPriceExtRepository repository) {
        this.repository = repository;
    }

    public List<ProductOfferPriceExt> findAll() {
        return repository.findAll();
    }

    public ProductOfferPriceExt findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductOfferPriceExt not found: " + id));
    }
}
