package com.jim.oms.order.client;

import lombok.Getter;

/**
 * 调用 WMS integration 产生的异常。{@code code} 尽量保留 WMS 返回的业务码（400/404/409/503 等），
 * {@code msg} 保留 WMS 原文，供上层原样呈现给调用方，不归一成统一错误。
 */
@Getter
public class WmsClientException extends RuntimeException {

    private final Integer code;

    public WmsClientException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public WmsClientException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
