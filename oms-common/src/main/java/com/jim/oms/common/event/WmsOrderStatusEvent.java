package com.jim.oms.common.event;

import lombok.Data;

import java.io.Serializable;

/**
 * WMS 单据状态事件 payload（topic {@code wms-order-status}）。
 * payload 里只有 WMS 出库单号，没有 OMS 单号；OMS 靠本地保存的 wms_outbound_no 反查订单。
 */
@Data
public class WmsOrderStatusEvent implements Serializable {

    /** WMS 出库单号（同时是 Kafka 消息 key） */
    private String orderNo;

    /** WMS 单据状态：CREATED / PICKING / PICKED / FINISHED / CANCELLED */
    private String status;

    /** WMS 附带说明，可空 */
    private String message;
}
