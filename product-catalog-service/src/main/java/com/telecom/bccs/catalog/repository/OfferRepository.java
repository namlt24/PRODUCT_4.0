package com.telecom.bccs.catalog.repository;

import com.telecom.bccs.catalog.entity.OfferEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfferRepository extends JpaRepository<OfferEntity, String> {

    @Query("""
            SELECT o FROM OfferEntity o
            WHERE (:productId IS NULL OR o.productId = :productId)
            """)
    Page<OfferEntity> search(@Param("productId") String productId, Pageable pageable);
}
