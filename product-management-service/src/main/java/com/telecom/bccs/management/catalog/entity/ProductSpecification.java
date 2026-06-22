package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_SPECIFICATION. */
@Entity
@Table(name = "PRODUCT_SPECIFICATION")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductSpecification {

    @Id
    @Column(name = "PRODUCT_SPEC_ID")
    private Integer productSpecId;

    @Column(name = "PRODUCT_SPEC_TYPE_ID") private Integer productSpecTypeId;
    @Column(name = "NAME") private String name;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "STATUS") private String status;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "EFFECT_DATETIME") private LocalDateTime effectDatetime;
    @Column(name = "EXPIRE_DATETIME") private LocalDateTime expireDatetime;
    @Column(name = "SEPARATE_CHAR") private String separateChar;
    @Column(name = "START_LINE") private Integer startLine;
    @Column(name = "EXTEND") private String extend;
    @Column(name = "CODE") private String code;
    @Column(name = "TELECOM_SERVICE_ID") private Integer telecomServiceId;
}
