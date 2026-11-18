package com.jim.oms.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回体。结构与 WMS 的 {@code Result} 保持一致：{@code code} 为业务状态码，
 * HTTP 层通常仍是 200，调用方必须以 JSON 里的 {@code code} 为准。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg("ok");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
}
