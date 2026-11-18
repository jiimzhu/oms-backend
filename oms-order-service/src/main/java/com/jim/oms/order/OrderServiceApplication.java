package com.jim.oms.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务启动类。开启定时补偿扫描；扫描公共包以注册统一异常处理。
 */
@SpringBootApplication(scanBasePackages = "com.jim.oms")
@EnableDiscoveryClient
@EnableScheduling
@MapperScan("com.jim.oms.order.mapper")
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
