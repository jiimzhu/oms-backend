package com.jim.oms.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.oms.order.entity.OmsOrderItem;
import org.apache.ibatis.annotations.Mapper;

/** 订单明细 Mapper。 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OmsOrderItem> {
}
