package com.jim.oms.common.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 发给 WMS integration 的取消指令。{@code omsOrderNo} 与 {@code wmsOutboundNo} 不能同时为空，
 * 否则 WMS 返回 400；取消原因 {@code reason} 可空。
 */
@Data
public class WmsOutboundCancelCmd implements Serializable {

    /** OMS 业务单号（与 wmsOutboundNo 二选一） */
    private String omsOrderNo;

    /** WMS 出库单号（与 omsOrderNo 二选一） */
    private String wmsOutboundNo;

    private String reason;
}
