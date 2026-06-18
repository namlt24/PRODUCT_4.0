package com.telecom.bccs.catalog.repository;

import com.telecom.bccs.catalog.entity.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {

    @Query("""
            SELECT p FROM ProductEntity p
            WHERE (:category IS NULL OR p.category = :category)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<ProductEntity> search(@Param("category") String category,
                               @Param("status") String status,
                               Pageable pageable);
}
