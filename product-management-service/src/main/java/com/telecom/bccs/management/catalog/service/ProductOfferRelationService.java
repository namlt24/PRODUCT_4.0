package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductOfferRelation;
import com.telecom.bccs.management.catalog.repository.ProductOfferRelationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_OFFER_RELATION (chỉ tra cứu). */
@Service
public class ProductOfferRelationService {

    private final ProductOfferRelationRepository repository;

    public ProductOfferRelationService(ProductOfferRelationRepository repository) {
        this.repository = repository;
    }

    public List<ProductOfferRelation> findAll() {
        return repository.findAll();
    }

    public ProductOfferRelation findById(Integer id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductOfferRelation not found: " + id));
    }
}
