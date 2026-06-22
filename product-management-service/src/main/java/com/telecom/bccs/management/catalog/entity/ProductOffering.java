package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_OFFERING (offering gốc của catalog). Field-access + Jackson đọc field. */
@Entity
@Table(name = "PRODUCT_OFFERING")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductOffering {

    @Id
    @Column(name = "PRODUCT_OFFERING_ID")
    private Integer productOfferingId;

    @Column(name = "PRODUCT_SPEC_ID") private Integer productSpecId;
    @Column(name = "PRODUCT_OFFER_TYPE_ID") private Integer productOfferTypeId;
    @Column(name = "NAME") private String name;
    @Column(name = "CODE") private String code;
    @Column(name = "SUB_TYPE") private String subType;
    @Column(name = "TELECOM_SERVICE_ID") private Integer telecomServiceId;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "STATUS") private String status;
    @Column(name = "EFFECT_DATETIME") private LocalDateTime effectDatetime;
    @Column(name = "EXPIRE_DATETIME") private LocalDateTime expireDatetime;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "VERSION") private String version;
    @Column(name = "CHECK_SERIAL") private Short checkSerial;
    @Column(name = "CHECK_DEPOSIT") private Short checkDeposit;
    @Column(name = "UNIT") private String unit;
    @Column(name = "ACCOUNTING_MODEL_CODE") private String accountingModelCode;
    @Column(name = "ACCOUNTING_MODEL_NAME") private String accountingModelName;
    @Column(name = "ACCOUNTING_NAME") private String accountingName;
    @Column(name = "ACCOUNTING_CODE") private String accountingCode;
    @Column(name = "DEMO_DURATION") private Integer demoDuration;
    @Column(name = "IS_DEMO") private Short isDemo;
    @Column(name = "DEVICE_TYPE") private String deviceType;
    @Column(name = "TRANSCEIVER") private Short transceiver;
    @Column(name = "STOCK_MODEL_TYPE") private Short stockModelType;
    @Column(name = "OWNER_SHOP_ID") private Integer ownerShopId;
    @Column(name = "RETURN_STOCK_WHEN_CANCELLED") private String returnStockWhenCancelled;
    @Column(name = "RETURN_STOCK_WHEN_CANCELLED1") private String returnStockWhenCancelled1;
    @Column(name = "SAP_MATERIAL_NUMBER") private Integer sapMaterialNumber;
    @Column(name = "USAGE_ID") private String usageId;
    @Column(name = "DISTRIBUTE") private String distribute;
    @Column(name = "NUM_MONTH") private Integer numMonth;
    @Column(name = "IS_BUNDLE") private String isBundle;
    @Column(name = "SAP_PRODUCT_TYPE") private String sapProductType;
}
