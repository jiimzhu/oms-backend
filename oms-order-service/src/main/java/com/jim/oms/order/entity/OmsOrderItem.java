package com.jim.oms.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细。下单时固化商品快照（名称、价格、WMS 映射、库位、批次），避免后续主数据变动影响已下订单。
 */
@Data
@TableName("oms_order_item")
public class OmsOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String omsSkuCode;

    private String wmsSkuCode;

    private String name;

    private String warehouseCode;

    private String locationCode;

    private String batchNo;

    private BigDecimal qty;

    private BigDecimal price;

    private BigDecimal amount;
}
