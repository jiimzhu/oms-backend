package com.jim.oms.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.oms.order.entity.PushTask;
import org.apache.ibatis.annotations.Mapper;

/** 下发补偿任务 Mapper。 */
@Mapper
public interface PushTaskMapper extends BaseMapper<PushTask> {
}
