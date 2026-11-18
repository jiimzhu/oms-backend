package com.jim.oms.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WMS 状态事件落库记录。保留 partition、offset、key、原始 value，供取证与对账；
 * 即使 payload 里的 WMS 单号在本地找不到订单，也先落库再由对账环节标记。
 */
@Data
@TableName("oms_wms_event")
public class WmsEventRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String topic;

    /** Kafka 分区号（列名 kafka_partition，避开 MySQL 保留字 partition） */
    private Integer kafkaPartition;

    private Long eventOffset;

    private String messageKey;

    private String wmsOutboundNo;

    private String status;

    private String message;

    private String rawValue;

    private LocalDateTime consumeTime;

    private LocalDateTime createTime;
}
