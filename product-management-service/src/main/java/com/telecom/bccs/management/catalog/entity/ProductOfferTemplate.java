package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_OFFER_TEMPLATE. */
@Entity
@Table(name = "PRODUCT_OFFER_TEMPLATE")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductOfferTemplate {

    @Id
    @Column(name = "PRODUCT_OFFER_TEMPLATE_ID")
    private Long productOfferTemplateId;

    @Column(name = "PRODUCT_OFFERING_ID") private Long productOfferingId;
    @Column(name = "TEMPLATE_CODE") private String templateCode;
    @Column(name = "TEMPLATE_NAME") private String templateName;
    @Column(name = "STATUS") private Short status;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "CHANNEL_TYPE_ID") private Integer channelTypeId;
    @Column(name = "AREA_CODE") private String areaCode;
    @Column(name = "IMAGE_LINK") private String imageLink;
    @Column(name = "PHASE_HUB") private String phaseHub;
    @Column(name = "UNIT_PRICE") private Long unitPrice;
    @Column(name = "IS_SME_SERVICE") private Short isSmeService;
}
