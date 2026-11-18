package com.jim.oms.common.constant;

/**
 * WMS 出库单状态常量（摘自 wms-outbound-service 源码）。
 * OMS 只消费 {@code wms-order-status} 事件里的 status 字段，不假设未列出的状态。
 */
public final class WmsOrderStatus {

    private WmsOrderStatus() {
    }

    /** 已创建，未拣货 */
    public static final String CREATED = "CREATED";
    /** 拣货中 */
    public static final String PICKING = "PICKING";
    /** 部分出库 */
    public static final String PICKED = "PICKED";
    /** 出库完成 */
    public static final String FINISHED = "FINISHED";
    /** 已取消 */
    public static final String CANCELLED = "CANCELLED";
}
