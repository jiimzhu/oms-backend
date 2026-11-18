package com.jim.oms.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 外部 HTTP 客户端装配。连接超时与读超时从配置显式读取，不使用框架默认值。
 */
@Configuration
public class ClientConfig {

    @Bean
    public RestTemplate wmsRestTemplate(
            @Value("${oms.wms.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${oms.wms.read-timeout-ms:10000}") int readTimeout) {
        return buildRestTemplate(connectTimeout, readTimeout);
    }

    @Bean
    public RestTemplate productRestTemplate(
            @Value("${oms.product.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${oms.product.read-timeout-ms:5000}") int readTimeout) {
        return buildRestTemplate(connectTimeout, readTimeout);
    }

    private RestTemplate buildRestTemplate(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        RestTemplate restTemplate = new RestTemplate(factory);
        // 不抛出 4xx/5xx 异常，交由调用方解析 body 里的 code，避免丢失 WMS 原文
        restTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return restTemplate;
    }
}
