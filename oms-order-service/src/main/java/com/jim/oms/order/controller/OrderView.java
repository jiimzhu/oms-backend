package com.jim.oms.order.controller;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情返回视图：主单 + 明细 + 关联的 WMS 事件流水。
 */
@Data
public class OrderView {

    private String orderNo;
    private String buyer;
    private String remark;
    private BigDecimal totalAmount;
    private String status;
    private String wmsOutboundNo;
    private String lastError;
    private List<ItemView> items;
    private List<EventView> events;

    @Data
    public static class ItemView {
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

    @Data
    public static class EventView {
        private Integer kafkaPartition;
        private Long eventOffset;
        private String messageKey;
        private String status;
        private String message;
        private String rawValue;
        private LocalDateTime consumeTime;
    }
}
