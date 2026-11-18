package com.jim.oms.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待下发补偿任务。订单落库与任务在同一本地事务写入；事务外由 worker 调 WMS，
 * 进程崩溃重启后按 {@code nextRetryTime} 扫描补偿，避免「本地有单、WMS 无单且无人推进」。
 */
@Data
@TableName("oms_push_task")
public class PushTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** PENDING / FAILED / DONE / CONFLICT */
    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
