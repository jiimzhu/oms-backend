package com.jim.oms.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 商品服务启动类。扫描公共模块的异常处理与契约，Mapper 落在本服务包内。
 */
@SpringBootApplication(scanBasePackages = "com.jim.oms")
@EnableDiscoveryClient
@MapperScan("com.jim.oms.product.mapper")
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
