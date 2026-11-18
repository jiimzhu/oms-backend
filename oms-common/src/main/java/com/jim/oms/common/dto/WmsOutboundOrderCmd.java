package com.jim.oms.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 发给 WMS integration 的建单指令。字段与 WMS 的 {@code OmsOutboundOrderCmd} 一一对应，
 * 订单类型由 WMS 服务端固定为 SALE_OUT，本指令不携带订单类型。
 */
@Data
public class WmsOutboundOrderCmd implements Serializable {

    /** OMS 业务单号，同时是 WMS 幂等键 */
    @NotBlank(message = "omsOrderNo 不能为空")
    private String omsOrderNo;

    @NotBlank(message = "warehouseCode 不能为空")
    private String warehouseCode;

    /** 备注，可空 */
    private String remark;

    @Valid
    @NotNull(message = "明细不能为空")
    private List<Item> items;

    @Data
    public static class Item implements Serializable {

        @NotBlank(message = "skuCode 不能为空")
        private String skuCode;

        /** 库位编码，WMS 硬约束 @NotBlank */
        @NotBlank(message = "locationCode 不能为空")
        private String locationCode;

        /** 批次号，可空 */
        private String batchNo;

        @NotNull(message = "qty 不能为空")
        @DecimalMin(value = "0.000001", message = "qty 必须大于 0")
        private BigDecimal qty;
    }
}
