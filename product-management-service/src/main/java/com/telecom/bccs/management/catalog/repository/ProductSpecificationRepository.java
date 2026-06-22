package com.telecom.bccs.management.catalog.repository;

import com.telecom.bccs.management.catalog.entity.ProductSpecification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSpecificationRepository extends JpaRepository<ProductSpecification, Integer> {
}
