package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_OFFER_RELATION (quan hệ giữa các offering). */
@Entity
@Table(name = "PRODUCT_OFFER_RELATION")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductOfferRelation {

    @Id
    @Column(name = "PRODUCT_OFFER_RELATION_ID")
    private Integer productOfferRelationId;

    @Column(name = "RELATION_TYPE_ID") private Integer relationTypeId;
    @Column(name = "MAIN_OFFER_ID") private Integer mainOfferId;
    @Column(name = "RELATION_OFFER_ID") private Integer relationOfferId;
    @Column(name = "STATUS") private String status;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "CONFIG_PHASE") private String configPhase;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "EFFECT_DATETIME") private LocalDateTime effectDatetime;
    @Column(name = "EXPIRE_DATETIME") private LocalDateTime expireDatetime;
}
