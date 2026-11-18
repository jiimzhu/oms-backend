package com.jim.oms.order.client;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jim.oms.common.dto.WmsOutboundCancelCmd;
import com.jim.oms.common.dto.WmsOutboundOrderCmd;
import com.jim.oms.common.dto.WmsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * WMS integration 对接客户端。OMS 只通过 HTTP 调用本客户端访问 WMS，
 * 严禁直连 WMS 数据库或跨库读写。
 *
 * <p>WMS 的业务码（400/404/409/503 等）写在 JSON body 的 code 字段，HTTP 状态通常仍是 200；
 * 因此这里解析 body.code，失败时把 code 与原文 msg 一并包进 {@link WmsClientException} 透传，不归一化。</p>
 */
@Slf4j
@Component
public class WmsIntegrationClient {

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Value("${oms.wms.base-url:http://127.0.0.1:8089}")
    private String baseUrl;

    public WmsIntegrationClient(@Qualifier("wmsRestTemplate") RestTemplate restTemplate,
                                ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 向 WMS 登记并创建销售出库单。成功返回 WMS 出库单号；失败抛 {@link WmsClientException}。
     *
     * @return WMS 出库单号字符串
     */
    public String createOutboundOrder(WmsOutboundOrderCmd cmd) {
        String body;
        try {
            body = restTemplate.postForObject(
                    baseUrl + "/integration/oms/outbound/order", cmd, String.class);
        } catch (Exception e) {
            // 连接失败 / 读超时等网络层异常：按 503 处理，交给补偿任务重试
            throw new WmsClientException(503,
                    "调用 WMS 建单网络异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        WmsResult<String> result = parse(body, String.class);
        if (result == null || !result.isOk()) {
            int code = result == null || result.getCode() == null ? 503 : result.getCode();
            String msg = result == null || result.getMsg() == null ? "WMS 无响应" : result.getMsg();
            throw new WmsClientException(code, msg);
        }
        return result.getData();
    }

    /**
     * 通知 WMS 取消出库单。WMS 返回的 code/msg 原样透传为 {@link WmsClientException}。
     */
    public void cancelOutboundOrder(WmsOutboundCancelCmd cmd) {
        String body;
        try {
            body = restTemplate.postForObject(
                    baseUrl + "/integration/oms/outbound/cancel", cmd, String.class);
        } catch (Exception e) {
            throw new WmsClientException(503,
                    "调用 WMS 取消网络异常: " + e.getClass().getSimpleName(), e);
        }
        WmsResult<Void> result = parse(body, Void.class);
        if (result == null || !result.isOk()) {
            int code = result == null || result.getCode() == null ? 503 : result.getCode();
            String msg = result == null || result.getMsg() == null ? "WMS 无响应" : result.getMsg();
            throw new WmsClientException(code, msg);
        }
    }

    private <T> WmsResult<T> parse(String body, Class<T> dataClass) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametricType(WmsResult.class, dataClass);
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            log.warn("解析 WMS 返回体失败: {}", e.getMessage());
            return null;
        }
    }
}
