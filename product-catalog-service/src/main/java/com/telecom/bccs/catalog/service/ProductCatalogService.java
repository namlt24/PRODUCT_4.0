package com.telecom.bccs.catalog.service;

import com.telecom.bccs.catalog.entity.OfferEntity;
import com.telecom.bccs.catalog.entity.ProductEntity;
import com.telecom.bccs.catalog.model.OfferDto;
import com.telecom.bccs.catalog.model.ProductDto;
import com.telecom.bccs.catalog.repository.OfferRepository;
import com.telecom.bccs.catalog.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Read-side business logic. Single-entity reads go through the two-level cache; paged list
 * reads go straight to the DB (lists are large, query-specific and short-lived, so caching
 * them per filter combination would bloat memory for little hit-rate gain).
 */
@Service
public class ProductCatalogService {

    private static final String NS_PRODUCT = "product";
    private static final String NS_OFFER = "offer";

    private final ProductRepository productRepository;
    private final OfferRepository offerRepository;
    private final TwoLevelCacheService cache;

    public ProductCatalogService(ProductRepository productRepository,
                                 OfferRepository offerRepository,
                                 TwoLevelCacheService cache) {
        this.productRepository = productRepository;
        this.offerRepository = offerRepository;
        this.cache = cache;
    }

    public ProductDto getProduct(String id) {
        return cache.get(NS_PRODUCT, id, ProductDto.class,
                () -> productRepository.findById(id).map(this::toDto).orElse(null));
    }

    public OfferDto getOffer(String id) {
        return cache.get(NS_OFFER, id, OfferDto.class,
                () -> offerRepository.findById(id).map(this::toDto).orElse(null));
    }

    public Page<ProductDto> listProducts(String category, String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("code").ascending());
        return productRepository.search(category, status, pageable).map(this::toDto);
    }

    public Page<OfferDto> listOffers(String productId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("code").ascending());
        return offerRepository.search(productId, pageable).map(this::toDto);
    }

    private ProductDto toDto(ProductEntity e) {
        return new ProductDto(e.getId(), e.getCode(), e.getName(), e.getCategory(),
                e.getStatus(), e.getDescription(), e.getUpdatedAt());
    }

    private OfferDto toDto(OfferEntity e) {
        return new OfferDto(e.getId(), e.getProductId(), e.getCode(), e.getName(),
                e.getPrice(), e.getCurrency(), e.getBillingCycle(), e.getStatus());
    }
}
