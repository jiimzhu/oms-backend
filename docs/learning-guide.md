# 学习导读：顺着一条订单看代码

本文按调用顺序列出一条下单链路从 HTTP 入口到 WMS 调用、再到事件回写所经过的类与方法，便于对照源码阅读。包根为 `com.jim.oms`，模块路径相对 `D:\oms-new`。

## 1. 下单主链路

### 1.1 HTTP 入口

- 文件：`oms-order-service/src/main/java/com/jim/oms/order/controller/OrderController.java`
- 方法：`OrderController.create`，映射 `POST /api/orders`。
- 入参：`OrderCreateRequest`（`oms-common`），`@Valid` 触发参数校验；调用 `OrderService.createOrder`，结果包成 `Result.success`。

### 1.2 幂等校验与计价

- 文件：`oms-order-service/src/main/java/com/jim/oms/order/service/OrderService.java`
- 方法：`OrderService.createOrder`，带 `@Transactional`。
- 步骤：
  1. 按 `orderNo` 查 `oms_order`，已存在抛 `BusinessException(409, 业务单号重复...)`；数据库唯一键 `uk_order_no` 是并发兜底。
  2. 逐行调 `ProductQueryClient.getProduct` 校验商品存在、启用，并取价计算金额。
  3. 校验明细同仓（跨仓拒绝），汇总 `totalAmount`。

### 1.3 调商品服务

- 文件：`oms-order-service/src/main/java/com/jim/oms/order/client/ProductQueryClient.java`
- 方法：`ProductQueryClient.getProduct`，`GET /api/products/{omsSkuCode}`。
- 返回体反序列化为 `ProductView`；商品服务不可用抛 503，商品不存在透传商品服务的业务码。

- 文件：`oms-product-service/src/main/java/com/jim/oms/product/controller/ProductController.java`
- 方法：`ProductController.get` → `ProductService.getByOmsSkuCode`，从 `oms_product` 表读取。

### 1.4 本地事务落库

回到 `OrderService.createOrder`：

- 写 `oms_order`（状态 `PENDING_PUSH`）。
- 写 `oms_order_item` 明细。
- 写 `oms_push_task`（状态 `PENDING`，`next_retry_time` 立即到期）。
- 注册事务同步回调：`afterCommit` 调 `PushWorker.pushIfPending(orderNo)`。WMS 调用在事务之外，保证订单与任务先落库。

### 1.5 补偿 worker 认领并下发 WMS

- 文件：`oms-order-service/src/main/java/com/jim/oms/order/service/PushWorker.java`
- 首次入口：`pushIfPending`；定时扫描：`sweep`（`@Scheduled`，间隔 `oms.push.scan-interval-ms`）；单次处理：`pushOne`。
- `claim`：条件 UPDATE 把任务从 `PENDING/FAILED/超时 PROCESSING` 置为 `PROCESSING` 并续租约，只有抢到一行的线程才下发。
- `buildCmd`：按明细组装 `WmsOutboundOrderCmd`（omsOrderNo、warehouseCode、remark、items）。
- 调 WMS：`WmsIntegrationClient.createOutboundOrder`。

### 1.6 WMS 客户端

- 文件：`oms-order-service/src/main/java/com/jim/oms/order/client/WmsIntegrationClient.java`
- 方法：`createOutboundOrder`，`POST {oms.wms.base-url}/integration/oms/outbound/order`。
- 解析 `WmsResult`：`code=200` 返回 `data` 出库单号；否则抛 `WmsClientException(code, msg)`，保留 WMS 原文。
- 超时配置在 `client/ClientConfig.java` 的 `wmsRestTemplate`（连接 3000ms、读 10000ms），错误处理器不抛 4xx/5xx，交给 body.code 判断。

### 1.7 下发结果回写

回到 `PushWorker`：

- `handleSuccess`：条件 UPDATE 订单状态 `WAITING_WMS`、回填 `wms_outbound_no`、清空 `last_error`；任务置 `DONE`。
- `handleConflictOrDuplicate`：409 且本地有单号视为重复推送保留结果；本地无单号置 `CONFLICT` 待人工。
- `markRejected`：400 不可重试，任务不再排期。
- `markFailed`：503/网络异常，任务 `FAILED`，指数退避排下次重试。

## 2. 状态事件回传链路

- 文件：`oms-order-service/src/main/java/com/jim/oms/order/service/StatusEventConsumer.java`
- 方法：`onOrderStatus`，`@KafkaListener(topics = "wms-order-status", groupId = oms-order-service)`。
- 步骤：
  1. 解析 payload 为 `WmsOrderStatusEvent`；把 partition、offset、key、原始 value 落 `oms_wms_event`。
  2. 按 `event.orderNo`（WMS 出库单号）反查 `oms_order.wms_outbound_no`；找不到则保留事件流水并告警。
  3. `mapWmsStatus` 把 `PICKED/FINISHED/CANCELLED` 映射到 OMS 状态；中间态不推进；终态订单忽略后续事件。
  4. 更新订单状态并写日志。

## 3. 取消链路

- 入口：`OrderController.cancel` → `CancelService.cancel`。
- 文件：`oms-order-service/src/main/java/com/jim/oms/order/service/CancelService.java`
- 步骤：校验订单存在与终态；无 WMS 单号走 `localCancel`；有单号调 `WmsIntegrationClient.cancelOutboundOrder`，捕获 `WmsClientException` 后原样抛 `BusinessException(e.getCode(), e.getMessage)`；成功后 `localCancel` 置 `CANCELLED`、任务置 `CANCELLED`。
- WMS 客户端方法：`WmsIntegrationClient.cancelOutboundOrder`，`POST /integration/oms/outbound/cancel`。

## 4. 对账链路

- 入口：`OrderController.reconcile` → `ReconcileService.reconcile`。
- 文件：`oms-order-service/src/main/java/com/jim/oms/order/service/ReconcileService.java`
- 读取本地订单与事件流水，按 WMS 单号取 offset 最大的事件，比对状态，输出四类差异（见 `docs/architecture.md` 第 8 节）。

## 5. 常见问题对照

- 下单后一直 `PENDING_PUSH/FAILED`：看 `oms_push_task.last_error` 与 order 服务日志；多为 WMS 不可达，worker 会自动重试。
- 订单在 `CONFLICT`：WMS 已有单但本地拿不到单号，需人工核对，禁止换号重试。
- 事件收到了订单没变：确认事件 payload 的 `orderNo` 是否等于本地 `wms_outbound_no`；中间态事件不推进订单。
- 网关访问 500 且 body.code=500：多为路由重写未命中服务接口前缀，检查 `application.yml` 的 RewritePath。
