package com.jim.oms.order.client;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单服务从商品服务查询到的商品视图（客户端侧反序列化模型，字段与商品服务实体对齐）。
 */
@Data
public class ProductView implements Serializable {

    private String omsSkuCode;
    private String name;
    private BigDecimal price;
    private String wmsSkuCode;
    private String warehouseCode;
    private String locationCode;
    private String batchNo;
    /** 1 启用，0 停用 */
    private Integer enabled;
}
