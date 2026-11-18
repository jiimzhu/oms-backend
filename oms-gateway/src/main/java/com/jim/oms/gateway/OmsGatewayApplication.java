package com.jim.oms.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关启动类。基于 Spring Cloud Gateway，从注册中心发现 product / order 服务并按路径转发。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class OmsGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmsGatewayApplication.class, args);
    }
}
