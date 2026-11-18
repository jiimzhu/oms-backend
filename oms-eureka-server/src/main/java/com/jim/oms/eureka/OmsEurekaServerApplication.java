package com.jim.oms.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * OMS 注册中心启动类。只注册 OMS 自己的服务，不与 WMS 的 8761 互通。
 */
@SpringBootApplication
@EnableEurekaServer
public class OmsEurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmsEurekaServerApplication.class, args);
    }
}
