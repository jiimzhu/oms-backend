package com.jim.oms.order.controller;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 取消订单请求。reason 可空。 */
@Data
public class CancelRequest {

    @NotBlank(message = "orderNo 不能为空")
    private String orderNo;

    private String reason;
}
