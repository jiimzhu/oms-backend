package com.jim.oms.common.constant;

/**
 * Kafka topic 常量。topic 名与 WMS 保持一致，OMS 只消费不新建。
 */
public final class Topics {

    private Topics() {
    }

    /** WMS 单据状态事件：key 为 WMS 出库单号，payload 为 JSON {orderNo, status, message} */
    public static final String ORDER_STATUS = "wms-order-status";
}
