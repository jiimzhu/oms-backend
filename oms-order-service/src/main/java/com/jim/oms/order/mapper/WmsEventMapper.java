package com.jim.oms.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.oms.order.entity.WmsEventRecord;
import org.apache.ibatis.annotations.Mapper;

/** WMS 事件流水 Mapper。 */
@Mapper
public interface WmsEventMapper extends BaseMapper<WmsEventRecord> {
}
