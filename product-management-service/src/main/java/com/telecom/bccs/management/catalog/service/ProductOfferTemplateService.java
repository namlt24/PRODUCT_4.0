package com.telecom.bccs.management.catalog.service;

import com.telecom.bccs.management.catalog.entity.ProductOfferTemplate;
import com.telecom.bccs.management.catalog.repository.ProductOfferTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Đọc PRODUCT_OFFER_TEMPLATE (chỉ tra cứu). */
@Service
public class ProductOfferTemplateService {

    private final ProductOfferTemplateRepository repository;

    public ProductOfferTemplateService(ProductOfferTemplateRepository repository) {
        this.repository = repository;
    }

    public List<ProductOfferTemplate> findAll() {
        return repository.findAll();
    }

    public ProductOfferTemplate findById(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ProductOfferTemplate not found: " + id));
    }
}
