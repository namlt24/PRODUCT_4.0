package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_OFFER_PRICE (giá của offering). */
@Entity
@Table(name = "PRODUCT_OFFER_PRICE")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductOfferPrice {

    @Id
    @Column(name = "PRODUCT_OFFER_PRICE_ID")
    private Long productOfferPriceId;

    @Column(name = "PRODUCT_OFFERING_ID") private Integer productOfferingId;
    @Column(name = "PRICE_POLICY_ID") private Integer pricePolicyId;
    @Column(name = "PRICE_TYPE_ID") private Integer priceTypeId;
    @Column(name = "NAME") private String name;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "PRICE") private Long price;
    @Column(name = "VAT") private Integer vat;
    @Column(name = "PLEDGE_AMOUNT") private Integer pledgeAmount;
    @Column(name = "PLEDGE_TIME") private Integer pledgeTime;
    @Column(name = "PRIOR_PAY") private Integer priorPay;
    @Column(name = "STATUS") private String status;
    @Column(name = "EFFECT_DATETIME") private LocalDateTime effectDatetime;
    @Column(name = "EXPIRE_DATETIME") private LocalDateTime expireDatetime;
    @Column(name = "PRIORITY") private Short priority;
    @Column(name = "EFFECT_TYPE") private String effectType;
    @Column(name = "CRON_EXPRESSION") private String cronExpression;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "PROGRAM_CODE") private String programCode;
    @Column(name = "PROGRAM_MONTH") private Integer programMonth;
    @Column(name = "IS_SELECT_ALL_SHOP") private String isSelectAllShop;
    @Column(name = "LIMITED") private Short limited;
}
