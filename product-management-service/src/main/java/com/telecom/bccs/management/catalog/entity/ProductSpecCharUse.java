package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_SPEC_CHAR_USE (đặc tính được dùng trong một specification). */
@Entity
@Table(name = "PRODUCT_SPEC_CHAR_USE")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductSpecCharUse {

    @Id
    @Column(name = "PROD_SPEC_CHAR_USE_ID")
    private Integer prodSpecCharUseId;

    @Column(name = "ORDER_CHAR") private Integer orderChar;
    @Column(name = "PRODUCT_SPEC_ID") private Integer productSpecId;
    @Column(name = "PRODUCT_SPEC_CHAR_ID") private Integer productSpecCharId;
    @Column(name = "PRODUCT_SPEC_CHAR_VALUE_ID") private Integer productSpecCharValueId;
    @Column(name = "STATUS") private String status;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "SYSTEM_TYPE") private String systemType;
    @Column(name = "SPECIFIC_VALUE") private String specificValue;
    @Column(name = "CONFIG_PHASE") private String configPhase;
    @Column(name = "MIN") private Integer min;
    @Column(name = "MAX") private Integer max;
    @Column(name = "IS_REQUIRED") private String isRequired;
    @Column(name = "NOTE") private String note;
}
