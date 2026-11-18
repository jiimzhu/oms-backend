package com.jim.oms.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 下单请求。{@code orderNo} 是 OMS 业务单号，也是幂等键与 WMS 侧幂等键；
 * 明细用 OMS 销售编码（{@code omsSkuCode}），由 OMS 商品主数据映射到 WMS skuCode。
 */
@Data
public class OrderCreateRequest implements Serializable {

    @NotBlank(message = "业务单号 orderNo 不能为空")
    private String orderNo;

    @NotBlank(message = "买家 buyer 不能为空")
    private String buyer;

    /** 备注，可空，原样透传给 WMS 的 remark */
    private String remark;

    @Valid
    @NotEmpty(message = "明细不能为空")
    private List<Item> items;

    @Data
    public static class Item implements Serializable {

        @NotBlank(message = "omsSkuCode 不能为空")
        private String omsSkuCode;

        @NotNull(message = "qty 不能为空")
        private BigDecimal qty;
    }
}
