package com.telecom.bccs.management.repository;

import com.telecom.bccs.management.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {
    boolean existsByCode(String code);
}
