package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_OFFER_PRICE_EXT (thuộc tính mở rộng của giá). */
@Entity
@Table(name = "PRODUCT_OFFER_PRICE_EXT")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductOfferPriceExt {

    @Id
    @Column(name = "PRODUCT_OFFER_PRICE_EXT_ID")
    private Integer productOfferPriceExtId;

    @Column(name = "PRODUCT_OFFER_PRICE_ID") private Integer productOfferPriceId;
    // KEY là từ khóa MariaDB -> bọc backtick để Hibernate trích dẫn đúng cột
    @Column(name = "`KEY`") private String key;
    @Column(name = "VALUE") private String value;
    @Column(name = "STATUS") private String status;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "CHECK_DISPLAY") private String checkDisplay;
    @Column(name = "FROM_VALUE") private Integer fromValue;
    @Column(name = "TO_VALUE") private Integer toValue;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "EFFECT_DATETIME") private LocalDateTime effectDatetime;
    @Column(name = "EXPIRE_DATETIME") private LocalDateTime expireDatetime;
}
