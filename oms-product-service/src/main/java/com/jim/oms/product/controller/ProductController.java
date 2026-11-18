package com.jim.oms.product.controller;

import com.jim.oms.common.dto.ProductUpsertRequest;
import com.jim.oms.common.result.Result;
import com.jim.oms.product.entity.Product;
import com.jim.oms.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品接口：维护与查询。订单服务下单前调用 {@code GET /api/products/{code}} 做校验与计价。
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping("/upsert")
    public Result<Product> upsert(@Valid @RequestBody ProductUpsertRequest request) {
        return Result.success(productService.upsert(request));
    }

    @GetMapping("/{omsSkuCode}")
    public Result<Product> get(@PathVariable String omsSkuCode) {
        return Result.success(productService.getByOmsSkuCode(omsSkuCode));
    }

    @GetMapping("/list")
    public Result<List<Product>> list() {
        return Result.success(productService.listAll());
    }
}
