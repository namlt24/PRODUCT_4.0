package com.telecom.bccs.management.service;

import com.telecom.bccs.management.entity.ProductEntity;
import com.telecom.bccs.management.kafka.CatalogEventPublisher;
import com.telecom.bccs.management.model.CatalogChangeEvent;
import com.telecom.bccs.management.model.ProductRequest;
import com.telecom.bccs.management.model.ProductResponse;
import com.telecom.bccs.management.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Write-side lifecycle management. The change event is published only AFTER the DB transaction
 * commits (via TransactionSynchronization), so the catalog service never evicts its cache for a
 * write that ends up rolled back.
 */
@Service
public class ProductManagementService {

    private final ProductRepository repository;
    private final CatalogEventPublisher eventPublisher;

    public ProductManagementService(ProductRepository repository, CatalogEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ProductResponse create(ProductRequest req) {
        if (repository.existsByCode(req.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product code already exists: " + req.code());
        }
        String id = UUID.randomUUID().toString();
        ProductEntity entity = new ProductEntity(id, req.code(), req.name(),
                req.category(), req.status(), req.description());
        repository.save(entity);
        publishAfterCommit("CREATED", id);
        return ProductResponse.from(entity);
    }

    @Transactional
    public ProductResponse update(String id, ProductRequest req) {
        ProductEntity entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
        entity.setCode(req.code());
        entity.setName(req.name());
        entity.setCategory(req.category());
        entity.setStatus(req.status());
        entity.setDescription(req.description());
        repository.save(entity);
        publishAfterCommit("UPDATED", id);
        return ProductResponse.from(entity);
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id);
        }
        repository.deleteById(id);
        publishAfterCommit("DELETED", id);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(String id) {
        return repository.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
    }

    private void publishAfterCommit(String changeType, String id) {
        CatalogChangeEvent event = new CatalogChangeEvent("PRODUCT", id, changeType, System.currentTimeMillis());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(event);
                }
            });
        } else {
            eventPublisher.publish(event);
        }
    }
}
