-- OMS 商品主数据：销售视角商品，同时记录对应 WMS 的 skuCode / 仓库 / 库位 / 批次。
CREATE TABLE IF NOT EXISTS oms_product (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    oms_sku_code  VARCHAR(64)  NOT NULL COMMENT 'OMS 销售编码',
    name          VARCHAR(128) NOT NULL COMMENT '商品名称',
    price         DECIMAL(18,2) NOT NULL COMMENT '销售价格',
    wms_sku_code  VARCHAR(64)  NOT NULL COMMENT '对应 WMS skuCode',
    warehouse_code VARCHAR(64) NOT NULL COMMENT '发货仓库编码',
    location_code VARCHAR(64) NOT NULL COMMENT '默认库位编码（WMS 建单硬约束）',
    batch_no      VARCHAR(64)  DEFAULT NULL COMMENT '批次号，可空',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
    create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_oms_sku_code (oms_sku_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OMS 商品主数据';
