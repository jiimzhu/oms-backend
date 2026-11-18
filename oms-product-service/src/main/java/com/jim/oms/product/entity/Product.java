package com.jim.oms.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OMS 商品主数据实体。一行对应一个销售编码，价格与 WMS 主数据映射都落在本行。
 */
@Data
@TableName("oms_product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** OMS 销售编码，唯一 */
    private String omsSkuCode;

    private String name;

    /** 销售价格 */
    private BigDecimal price;

    /** 对应 WMS 的 skuCode */
    private String wmsSkuCode;

    /** 发货仓库编码 */
    private String warehouseCode;

    /** 默认库位编码 */
    private String locationCode;

    /** 批次号，可空 */
    private String batchNo;

    /** 是否启用：1 启用，0 停用 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
