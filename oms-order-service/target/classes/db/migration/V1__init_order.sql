-- OMS 订单域初始化：订单主表、明细、待下发补偿任务、WMS 事件流水（取证对账用）。

CREATE TABLE IF NOT EXISTS oms_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL COMMENT 'OMS 业务单号（幂等键）',
    buyer           VARCHAR(128) NOT NULL COMMENT '买家',
    remark          VARCHAR(500) DEFAULT NULL COMMENT '备注',
    total_amount    DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '订单总金额',
    status          VARCHAR(20)  NOT NULL COMMENT '状态机：PENDING_PUSH/WAITING_WMS/PICKED/FINISHED/CANCELLED/CONFLICT/FAILED',
    wms_outbound_no VARCHAR(64)  DEFAULT NULL COMMENT 'WMS 出库单号，下发成功后回填',
    last_error      VARCHAR(500) DEFAULT NULL COMMENT '最近一次错误信息',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OMS 订单主表';

CREATE TABLE IF NOT EXISTS oms_order_item (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no       VARCHAR(64)  NOT NULL,
    oms_sku_code   VARCHAR(64)  NOT NULL COMMENT 'OMS 销售编码',
    wms_sku_code   VARCHAR(64)  NOT NULL COMMENT '映射后的 WMS skuCode',
    name           VARCHAR(128) DEFAULT NULL,
    warehouse_code VARCHAR(64)  NOT NULL,
    location_code  VARCHAR(64)  NOT NULL,
    batch_no       VARCHAR(64)  DEFAULT NULL,
    qty            DECIMAL(18,3) NOT NULL,
    price          DECIMAL(18,2) NOT NULL,
    amount         DECIMAL(18,2) NOT NULL,
    KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OMS 订单明细';

CREATE TABLE IF NOT EXISTS oms_push_task (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no         VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL COMMENT 'PENDING/FAILED/DONE/CONFLICT',
    retry_count      INT NOT NULL DEFAULT 0,
    next_retry_time  DATETIME DEFAULT NULL,
    last_error       VARCHAR(500) DEFAULT NULL,
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_order_no (order_no),
    KEY idx_status_next_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WMS 下发补偿任务';

CREATE TABLE IF NOT EXISTS oms_wms_event (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic            VARCHAR(64)  NOT NULL,
    kafka_partition  INT NOT NULL,
    event_offset     BIGINT NOT NULL,
    message_key      VARCHAR(128) DEFAULT NULL,
    wms_outbound_no  VARCHAR(64)  DEFAULT NULL COMMENT 'payload.orderNo，WMS 出库单号',
    status           VARCHAR(20)  DEFAULT NULL,
    message          VARCHAR(500) DEFAULT NULL,
    raw_value        LONGTEXT NOT NULL COMMENT '原始消息体，取证对账用',
    consume_time     DATETIME DEFAULT NULL,
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_wms_outbound_no (wms_outbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WMS 事件流水';
