package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductOfferRelationDetail;
import com.telecom.bccs.management.catalog.repository.ProductOfferRelationDetailRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_OFFER_RELATION_DETAIL (chỉ tra cứu). */
@Service
public class ProductOfferRelationDetailService {

    private final ProductOfferRelationDetailRepository repository;

    public ProductOfferRelationDetailService(ProductOfferRelationDetailRepository repository) {
        this.repository = repository;
    }

    public List<ProductOfferRelationDetail> findAll() {
        return repository.findAll();
    }

    public ProductOfferRelationDetail findById(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductOfferRelationDetail not found: " + id));
    }
}
