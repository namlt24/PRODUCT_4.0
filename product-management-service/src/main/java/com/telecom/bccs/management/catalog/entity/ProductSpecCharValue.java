package com.telecom.bccs.management.catalog.entity;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Bản đồ JPA cho bảng PRODUCT_SPEC_CHAR_VALUE (giá trị cho phép của một đặc tính). */
@Entity
@Table(name = "PRODUCT_SPEC_CHAR_VALUE")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProductSpecCharValue {

    @Id
    @Column(name = "PRODUCT_SPEC_CHAR_VALUE_ID")
    private Integer productSpecCharValueId;

    @Column(name = "PRODUCT_SPEC_CHAR_ID") private Integer productSpecCharId;
    @Column(name = "VALUE_TYPE") private String valueType;
    @Column(name = "IS_DEFAULT") private Short isDefault;
    @Column(name = "VALUE") private String value;
    @Column(name = "UNIT_OF_MEASURE") private String unitOfMeasure;
    @Column(name = "VALUE_FROM") private String valueFrom;
    @Column(name = "VALUE_TO") private String valueTo;
    @Column(name = "RANGE_INTERVAL") private String rangeInterval;
    @Column(name = "STATUS") private String status;
    @Column(name = "CREATE_USER") private String createUser;
    @Column(name = "CREATE_DATETIME") private LocalDateTime createDatetime;
    @Column(name = "UPDATE_USER") private String updateUser;
    @Column(name = "UPDATE_DATETIME") private LocalDateTime updateDatetime;
    @Column(name = "NAME") private String name;
    @Column(name = "SPECIFIC_VALUE") private String specificValue;
    @Column(name = "NOTE") private String note;
}
