package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_SPEC_CHAR (đặc tính của product specification). */
@Entity
@Table(name = "PRODUCT_SPEC_CHAR")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductSpecChar {

    @Id
    @Column(name = "PRODUCT_SPEC_CHAR_ID")
    private Integer productSpecCharId;

    @Column(name = "NAME") private String name;
    @Column(name = "DESCRIPTION") private String description;
    @Column(name = "VALUE_TYPE") private String valueType;
    @Column(name = "CHAR_TYPE") private String charType;
    @Column(name = "MIN_CARDINALITY") private Integer minCardinality;
    @Column(name = "MAX_CARDINALITY") private Integer maxCardinality;
    @Column(name = "STATUS") private String status;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "CODE") private String code;
    @Column(name = "PRODUCT_SPEC_CHAR_TYPE_ID") private String productSpecCharTypeId;
    @Column(name = "VALUE_SET_TYPE") private Short valueSetType;
    @Column(name = "RESPONSE_CLASS") private String responseClass;
    @Column(name = "SQL_QUERY") private String sqlQuery;
    @Column(name = "DISPLAY_OBJECT") private String displayObject;
    @Column(name = "VALUE_OBJECT") private String valueObject;
    @Column(name = "SOLR_QUERY") private String solrQuery;
    @Column(name = "SOLR_CORE") private String solrCore;
    @Column(name = "SOLR_SCHEMA") private String solrSchema;
    @Column(name = "DATA_TYPE") private String dataType;
    @Column(name = "WS_WSDL") private String wsWsdl;
    @Column(name = "TEMPLATE_REQUEST") private String templateRequest;
    @Column(name = "VALIDATE_PATTERN") private String validatePattern;
    @Column(name = "EXT_DATA") private String extData;
    @Column(name = "NOTE") private String note;
}
