package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_OFFER_RELATION_DETAIL. */
@Entity
@Table(name = "PRODUCT_OFFER_RELATION_DETAIL")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductOfferRelationDetail {

    @Id
    @Column(name = "PRODUCT_OFFER_RELATION_DETAIL")
    private Long productOfferRelationDetail;

    @Column(name = "PRODUCT_OFFER_RELATION_ID") private Integer productOfferRelationId;
    @Column(name = "PRODUCT_SPEC_CHAR_ID") private Integer productSpecCharId;
    @Column(name = "PRODUCT_SPEC_CHAR_VALUE_ID") private Integer productSpecCharValueId;
    @Column(name = "STATUS") private String status;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "SPECIFIC_VALUE") private String specificValue;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "EFFECT_DATETIME") private LocalDateTime effectDatetime;
    @Column(name = "EXPIRE_DATETIME") private LocalDateTime expireDatetime;
}
