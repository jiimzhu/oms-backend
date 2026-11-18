package com.jim.oms.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OMS 订单主表。{@code orderNo} 为幂等键；{@code wmsOutboundNo} 在下发成功后回填。
 */
@Data
@TableName("oms_order")
public class OmsOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String buyer;

    private String remark;

    private BigDecimal totalAmount;

    /** 订单状态机，见 {@code OmsOrderStatus} */
    private String status;

    private String wmsOutboundNo;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
