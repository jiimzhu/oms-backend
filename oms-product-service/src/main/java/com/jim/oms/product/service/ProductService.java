package com.jim.oms.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jim.oms.common.dto.ProductUpsertRequest;
import com.jim.oms.common.exception.BusinessException;
import com.jim.oms.product.entity.Product;
import com.jim.oms.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品主数据服务：按 omsSkuCode 新增或覆盖（upsert），并提供查询。
 * 订单服务在下单前通过本服务的 HTTP 查询接口校验商品存在且启用。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;

    /** 新增或更新商品；omsSkuCode 已存在则覆盖价格与 WMS 映射，不存在则插入。 */
    @Transactional(rollbackFor = Exception.class)
    public Product upsert(ProductUpsertRequest request) {
        Product existing = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getOmsSkuCode, request.getOmsSkuCode()));
        boolean enabled = request.getEnabled() == null || request.getEnabled();

        if (existing == null) {
            Product product = new Product();
            copyRequest(product, request, enabled);
            product.setCreateTime(LocalDateTime.now());
            product.setUpdateTime(LocalDateTime.now());
            productMapper.insert(product);
            return product;
        }
        copyRequest(existing, request, enabled);
        existing.setUpdateTime(LocalDateTime.now());
        productMapper.updateById(existing);
        return existing;
    }

    /** 按销售编码查询；不存在抛 404。 */
    public Product getByOmsSkuCode(String omsSkuCode) {
        Product product = productMapper.selectOne(new LambdaQueryWrapper<Product>()
                .eq(Product::getOmsSkuCode, omsSkuCode));
        if (product == null) {
            throw new BusinessException(404, "商品不存在: " + omsSkuCode);
        }
        return product;
    }

    public List<Product> listAll() {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                .orderByAsc(Product::getOmsSkuCode));
    }

    private void copyRequest(Product product, ProductUpsertRequest request, boolean enabled) {
        product.setOmsSkuCode(request.getOmsSkuCode());
        product.setName(request.getName());
        product.setPrice(request.getPrice());
        product.setWmsSkuCode(request.getWmsSkuCode());
        product.setWarehouseCode(request.getWarehouseCode());
        product.setLocationCode(request.getLocationCode());
        product.setBatchNo(request.getBatchNo());
        product.setEnabled(enabled ? 1 : 0);
    }
}
