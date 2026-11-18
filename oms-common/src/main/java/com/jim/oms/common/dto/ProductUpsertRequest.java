package com.jim.oms.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * OMS 销售视角商品维护请求。商品同时记录对应 WMS 的 skuCode、发货仓库与默认库位：
 * 因为 WMS 建出库单时 locationCode 是 @NotBlank 硬约束，OMS 必须能给出具体库位。
 */
@Data
public class ProductUpsertRequest implements Serializable {

    @NotBlank(message = "omsSkuCode 不能为空")
    private String omsSkuCode;

    @NotBlank(message = "name 不能为空")
    private String name;

    @NotNull(message = "price 不能为空")
    private BigDecimal price;

    @NotBlank(message = "wmsSkuCode 不能为空")
    private String wmsSkuCode;

    @NotBlank(message = "warehouseCode 不能为空")
    private String warehouseCode;

    @NotBlank(message = "locationCode 不能为空")
    private String locationCode;

    /** 批次号，可空 */
    private String batchNo;

    /** 是否启用，默认 true */
    private Boolean enabled;
}
