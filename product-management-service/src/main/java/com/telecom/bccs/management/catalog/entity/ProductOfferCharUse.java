package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_OFFER_CHAR_USE. */
@Entity
@Table(name = "PRODUCT_OFFER_CHAR_USE")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductOfferCharUse {

    @Id
    @Column(name = "PRODUCT_OFFER_CHAR_USE_ID")
    private Integer productOfferCharUseId;

    @Column(name = "ORDER_CHAR") private Integer orderChar;
    @Column(name = "TYPE") private String type;
    @Column(name = "PRODUCT_OFFERING_ID") private Integer productOfferingId;
    @Column(name = "PRODUCT_SPEC_CHAR_VALUE_ID") private Integer productSpecCharValueId;
    @Column(name = "PRODUCT_SPEC_CHAR_ID") private Integer productSpecCharId;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "STATUS") private String status;
    @Column(name = "SPECIFIC_VALUE") private String specificValue;
    @Column(name = "EFFECT_DATETIME") private LocalDateTime effectDatetime;
    @Column(name = "EXPIRE_DATETIME") private LocalDateTime expireDatetime;
    @Column(name = "LIMITED") private Short limited;
    @Column(name = "DESCRIPTION") private String description;
}
