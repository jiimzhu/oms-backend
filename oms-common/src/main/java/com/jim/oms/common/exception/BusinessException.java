package com.jim.oms.common.exception;

import lombok.Getter;

/**
 * OMS 业务异常。{@code code} 用于对齐 HTTP 语义（400 参数/主数据、409 冲突、404 不存在、503 下游不可用等），
 * 与 WMS 透传过来的 code 保持一致，不做归一化抹平。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
