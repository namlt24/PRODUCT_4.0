package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng TEMPLATE_CHAR_USE. */
@Entity
@Table(name = "TEMPLATE_CHAR_USE")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class TemplateCharUse {

    @Id
    @Column(name = "TEMPLATE_CHAR_USE_ID")
    private Long templateCharUseId;

    @Column(name = "PRODUCT_OFFER_TEMPLATE_ID") private Long productOfferTemplateId;
    @Column(name = "PRODUCT_SPEC_CHAR_ID") private Long productSpecCharId;
    @Column(name = "SPECIFIC_VALUE") private String specificValue;
    @Column(name = "CONFIG_PHASE") private String configPhase;
    @Column(name = "NO") private Integer no;
    @Column(name = "STATUS") private Short status;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "SUB_VALUE") private String subValue;
}
