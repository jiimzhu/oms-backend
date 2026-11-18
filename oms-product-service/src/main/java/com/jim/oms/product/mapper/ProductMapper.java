package com.jim.oms.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.oms.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/** 商品表 Mapper，CRUD 由 MyBatis-Plus 提供。 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
