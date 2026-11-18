package com.jim.oms.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.oms.order.entity.OmsOrder;
import org.apache.ibatis.annotations.Mapper;

/** 订单主表 Mapper。 */
@Mapper
public interface OrderMapper extends BaseMapper<OmsOrder> {
}
