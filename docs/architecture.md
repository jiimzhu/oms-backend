# OMS 架构说明

## 1. 总体结构

OMS 按 database-per-service 拆分，独立注册到自己的 Eureka（8762），与 WMS 注册中心（8761）隔离。

```
                         ┌──────────────────────┐
          外部调用 ─────▶ │ oms-gateway :8092    │  路径路由，lb:// 转发
                         └──────────┬───────────┘
                 /product/**        │        /order/**
                    ┌───────────────┴───────────────┐
                    ▼                                 ▼
        ┌────────────────────┐             ┌────────────────────────┐
        │ product-service 8090│            │ order-service 8091      │
        │ jim_oms_product     │            │ jim_oms_order            │
        └─────────────────────┘            │  订单/明细/任务/事件流水  │
                                           └───────┬───────────┬───────┘
                         HTTP 调 WMS 建单/取消      │           │ 消费 Kafka
                                           ┌───────▼──────┐  ┌──▼─────────────┐
                                           │ WMS 8089     │  │ topic          │
                                           │ integration  │  │ wms-order-status│
                                           └──────────────┘  └────────────────┘
                         ┌──────────────────────┐
                         │ oms-eureka-server 8762│  只注册 OMS 服务
                         └──────────────────────┘
```

## 2. 模块分层

### 2.1 oms-common

跨服务共享契约，不含业务逻辑与数据库依赖：

- `result/Result.java`：统一返回体 `{code, msg, data}`，结构对齐 WMS；HTTP 成败不代表业务成败。
- `exception/BusinessException.java`：业务异常，携带业务码与消息。
- `exception/GlobalExceptionHandler.java`：把业务异常、参数校验异常、未捕获异常写回 Result，不归一成 500。
- `constant/OmsOrderStatus.java`：OMS 订单状态与终态集合。
- `constant/WmsOrderStatus.java`：WMS 状态常量（CREATED/PICKING/PICKED/FINISHED/CANCELLED）。
- `constant/Topics.java`：Kafka topic 名。
- `dto/`：下单请求、商品请求、发往 WMS 的建单/取消指令、WMS 返回体模型。
- `event/WmsOrderStatusEvent.java`：状态事件 payload 模型。

### 2.2 oms-product-service

维护销售视角商品主数据。每条商品记录 OMS 销售编码、名称、价格、对应 WMS skuCode、发货仓库编码、默认库位编码、批次、是否启用。因为 WMS 建出库单时 `locationCode` 是必填硬约束，商品表必须落库位与仓库。

### 2.3 oms-order-service

订单主链路，是本工程核心。内部分为控制器、客户端、服务、补偿任务、事件消费者、对账几个部分：

- `controller/OrderController.java`：下单、详情、取消、对账接口。
- `client/ProductQueryClient.java`：HTTP 调商品服务校验与取价。
- `client/WmsIntegrationClient.java`：HTTP 调 WMS integration 建单、取消；解析 body.code，失败包成 `WmsClientException` 保留原 code 与原文。
- `client/ClientConfig.java`：两个带显式连接/读超时的 RestTemplate，关闭 4xx/5xx 抛异常，保留 WMS 原文。
- `service/OrderService.java`：下单事务与计价。
- `service/PushWorker.java`：WMS 下发与补偿 worker。
- `service/StatusEventConsumer.java`：消费状态事件并推进订单。
- `service/CancelService.java`：取消链路。
- `service/ReconcileService.java`：本地对账。
- `entity/`、`mapper/`：订单、明细、补偿任务、事件流水四张表。

### 2.4 oms-gateway

基于 Spring Cloud Gateway，从 Eureka 发现 product/order 实例，按路径转发。`/product/**` 重写到 `/api/products/**`，`/order/**` 重写到 `/api/orders/**`。

## 3. 数据模型（jim_oms_order）

| 表 | 作用 | 关键列 |
| --- | --- | --- |
| `oms_order` | 订单主表 | `order_no`（唯一键，幂等键）、`total_amount`、`status`、`wms_outbound_no`、`last_error` |
| `oms_order_item` | 订单明细 | `order_no`、`oms_sku_code`、`wms_sku_code`、`warehouse_code`、`location_code`、`batch_no`、`qty`、`price`、`amount` |
| `oms_push_task` | 待下发补偿任务 | `order_no`、`status`、`retry_count`、`next_retry_time`、`last_error` |
| `oms_wms_event` | WMS 事件流水（取证对账） | `topic`、`kafka_partition`、`event_offset`、`message_key`、`wms_outbound_no`、`status`、`message`、`raw_value`、`consume_time` |

商品库 `jim_oms_product.oms_product`：`oms_sku_code` 唯一，保存价格与 WMS 主数据映射。

## 4. 状态机

OMS 订单状态：`PENDING_PUSH`、`WAITING_WMS`、`PICKED`、`FINISHED`、`CANCELLED`、`CONFLICT`、`FAILED`。终态集合为 `FINISHED`、`CANCELLED`、`CONFLICT`。

```
PENDING_PUSH ──下发成功回填单号──▶ WAITING_WMS ──收到 PICKED 事件──▶ PICKED
                                   │                                  │
                                   ├──收到 FINISHED 事件──▶ FINISHED ◀─┘
                                   ├──收到 CANCELLED 事件──▶ CANCELLED
                                   └──409 且本地无单号──▶ CONFLICT（待人工）
任意在途状态 ──可重试失败──▶ FAILED（记录错误，补偿任务按退避重试）
```

WMS 中间态 `CREATED`、`PICKING`、`CONFIRMING` 不在事件中出现；事件只映射 `PICKED`、`FINISHED`、`CANCELLED`。已终态订单收到后续事件直接忽略。

## 5. 下单与下发补偿（崩溃兜底）

1. `OrderService.createOrder` 在同一本地事务内写订单、明细、待下发任务；事务提交后注册 `afterCommit` 回调触发首次下发。WMS 调用不在订单事务内。
2. `PushWorker.sweep` 定时扫描到期任务；`pushOne` 先条件认领：把 `PENDING/FAILED/超时 PROCESSING` 置为 `PROCESSING` 并续租约，只有影响一行的线程才真正下发，避免首推与定时扫描并发重复推送。
3. 成功：回填 WMS 出库单号，订单进 `WAITING_WMS`，任务置 `DONE`，订单行 `last_error` 显式清空；任务行 `oms_push_task.last_error` 保留首次失败文本作为记录性残留，不代表状态未恢复（是否在任务置 DONE 时一并清空该字段为待改进项）。
4. 失败分支：
   - 400 主数据/参数错误：不可重试，任务 `FAILED` 且不再排期，订单 `FAILED` 保留错误。
   - 409：本地已有单号视为重复推送，保留既有结果；本地无单号置 `CONFLICT` 待人工，禁止换业务单号重试。
   - 503 与网络异常：任务 `FAILED`，按指数退避安排下次重试，订单保留错误信息。
5. 进程被杀或 WMS 暂时不可用后，重启扫描任务表自动补偿；任务表与订单同事务写入，避免「本地有单、WMS 无单、无人推进」的静默不一致。

## 6. 事件回传与取证

`StatusEventConsumer.onOrderStatus` 先把原始消息（topic、partition、offset、key、value）落 `oms_wms_event`，再按 payload 的 WMS 出库单号反查本地订单并映射推进状态。找不到对应订单时事件仍落库，由对账归类为 `EVENT_NO_ORDER`。

## 7. 取消链路

`CancelService.cancel`：终态拒绝；`CONFLICT` 要求先人工核对；尚无 WMS 单号的订单只做本地取消并终止任务；已有单号的订单调 WMS 取消接口，WMS 的 code/msg 原样抛给调用方，不吞掉、不归一化。成功后本地置 `CANCELLED`，任务置 `CANCELLED`。

## 8. 对账

`ReconcileService.reconcile` 只使用 OMS 本地订单与事件流水（WMS 无查询接口，OMS 也不直连 WMS 库）。差异类型：

- `STATUS_MISMATCH`：本地订单状态与最新一条 WMS 事件状态不一致。
- `CONFLICT_NO_OUTBOUND_NO`：409 冲突、无 WMS 单号，待人工。
- `EVENT_NO_ORDER`：事件在 OMS 找不到对应订单。
- `ORDER_NO_EVENT`：订单没有任何事件。

同一 WMS 单号取 offset 最大的一条作为最新状态。

## 9. 外部依赖与边界

- 只通过 HTTP 访问 WMS integration（8089），禁止直连 WMS 库、禁止跨库读写。
- 拣货、出库确认等是联调夹具，调用 WMS 公开 API（8084）驱动状态，不属于 OMS 业务依赖。
- 不向 WMS 库写数据；WMS 侧测试残留单据无法在 OMS 侧删除。
