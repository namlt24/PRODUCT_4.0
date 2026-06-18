package com.telecom.bccs.catalog.controller;

import com.telecom.bccs.catalog.model.OfferDto;
import com.telecom.bccs.catalog.model.PagedResponse;
import com.telecom.bccs.catalog.model.ProductDto;
import com.telecom.bccs.catalog.service.ProductCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * Read endpoints for 3rd-party data pull. Implements the operations defined in
 * {@code openapi/openapi.yaml}. Single-entity gets are served from the two-level cache;
 * an ETag/Cache-Control hint also lets well-behaved partners cache on their side.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "products")
public class ProductController {

    private final ProductCatalogService service;

    public ProductController(ProductCatalogService service) {
        this.service = service;
    }

    @GetMapping("/products")
    @Operation(summary = "List products (paged)")
    public PagedResponse<ProductDto> listProducts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return PagedResponse.from(service.listProducts(category, status, page, size));
    }

    @GetMapping("/products/{productId}")
    @Operation(summary = "Get a product by id")
    public ResponseEntity<ProductDto> getProduct(@PathVariable String productId) {
        ProductDto product = service.getProduct(productId);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + productId);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(product);
    }

    @GetMapping("/offers")
    @Operation(summary = "List offers (paged)")
    public PagedResponse<OfferDto> listOffers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String productId) {
        return PagedResponse.from(service.listOffers(productId, page, size));
    }

    @GetMapping("/offers/{offerId}")
    @Operation(summary = "Get an offer by id")
    public ResponseEntity<OfferDto> getOffer(@PathVariable String offerId) {
        OfferDto offer = service.getOffer(offerId);
        if (offer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found: " + offerId);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(offer);
    }
}
