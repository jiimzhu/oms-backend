package com.jim.oms.common.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * WMS 返回体的反序列化模型。注意：WMS 全局异常处理把 code 写进 JSON body，
 * HTTP 状态码通常仍是 200，因此必须读取 body 里的 code，不能只看 HTTP 状态。
 *
 * @param <T> data 类型
 */
@Data
public class WmsResult<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public boolean isOk() {
        return code != null && code == 200;
    }
}
