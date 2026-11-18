package com.jim.oms.order.client;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jim.oms.common.dto.WmsResult;
import com.jim.oms.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 调用 OMS 商品服务的查询客户端。下单前用它校验商品存在、启用并取价；
 * 商品服务不可用时抛 503，由下单链路按可重试异常处理。
 */
@Component
public class ProductQueryClient {

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Value("${oms.product.base-url:http://127.0.0.1:8090}")
    private String baseUrl;

    public ProductQueryClient(@Qualifier("productRestTemplate") RestTemplate restTemplate,
                              ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public ProductView getProduct(String omsSkuCode) {
        String body;
        try {
            body = restTemplate.getForObject(baseUrl + "/api/products/" + omsSkuCode, String.class);
        } catch (Exception e) {
            throw new BusinessException(503, "商品服务不可用: " + e.getClass().getSimpleName());
        }
        WmsResult<ProductView> result = parse(body, ProductView.class);
        if (result == null || !result.isOk() || result.getData() == null) {
            int code = result == null || result.getCode() == null ? 404 : result.getCode();
            String msg = result == null || result.getMsg() == null ? "商品服务无响应" : result.getMsg();
            throw new BusinessException(code, msg);
        }
        return result.getData();
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
            return null;
        }
    }
}
