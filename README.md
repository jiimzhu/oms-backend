# OMS 个人学习微服务工程

本工程在 `D:\oms-new` 从零搭建，用于学习订单主链路微服务拆分、与既有 WMS 的 HTTP 对接、Kafka 状态回传、崩溃补偿与对账。技术栈与同机 `D:\wms\wms-backend` 对齐；本工程只读访问 WMS 源码，不修改 `D:\wms` 下任何文件，不向 WMS 数据库写数据。

## 1. 技术栈与版本

版本取自 `D:\wms\wms-backend` 的父工程，不自选升降：

| 项 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17（Temurin 17.0.21） | 本机已安装 |
| Spring Boot | 3.2.5 | 与 WMS 一致 |
| Spring Cloud | 2023.0.2 | 版本号来自 WMS 父 pom 的 `spring-cloud.version` |
| MyBatis-Plus | 3.5.5 | 与 WMS 一致 |
| MySQL | 8.0 | `127.0.0.1:3306`，账号 root/root |
| Flyway | spring-boot-starter 管理 | 各服务独立库初始化迁移 |
| Eureka | netflix-eureka-server/client | OMS 独立注册中心，不与 WMS 的 8761 互通 |
| Spring Kafka | spring-kafka | 消费 `wms-order-status` |
| Redis | 当前未接入 | 见第 9 节「未完成与未使用项」 |

## 2. 模块、端口与数据库

| 模块 | 端口 | 数据库 | 职责 |
| --- | --- | --- | --- |
| oms-parent | 聚合 pom，无端口 | 无 | 依赖版本与 BOM 管理 |
| oms-common | 无端口 | 无 | 统一 Result、异常、状态常量、DTO、Kafka 事件模型 |
| oms-eureka-server | 8762 | 无 | OMS 注册中心 |
| oms-product-service | 8090 | `jim_oms_product` | 销售视角商品与价格，映射 WMS skuCode/仓库/库位/批次 |
| oms-order-service | 8091 | `jim_oms_order` | 下单、状态机、WMS 下发与补偿、事件回传、取消、对账 |
| oms-gateway | 8092 | 无 | 对外统一入口，按路径路由到 product 与 order |

端口约定：不占用 WMS 的 8761 与 8081–8089。库名只建 `jim_oms_product`、`jim_oms_order` 两个 OMS 自有库；不创建、不改动 `jim_wms_*` 表。

## 3. 模块依赖关系

```
oms-parent（pom，BOM 与版本管理）
├── oms-common（Result / 异常 / 常量 / DTO / 事件模型）
├── oms-eureka-server
├── oms-product-service ── 依赖 oms-common
├── oms-order-service    ── 依赖 oms-common
└── oms-gateway         ── 注册发现后 lb:// 转发，不引入业务 SDK
```

运行期调用关系：

- 外部调用网关 8092，或直连 product 8090 / order 8091。
- order-service 经 HTTP 调 product-service 校验商品并取价。
- order-service 经 HTTP 调 WMS integration（默认 `127.0.0.1:8089`）建出库单、通知取消；不直连 WMS 数据库。
- order-service 消费 Kafka topic `wms-order-status`，按 WMS 出库单号反查本地订单推进状态。

## 4. 启动顺序与脚本

前置中间件：MySQL 3306、Redis 6379（本工程未使用，但环境在运行）、Kafka 9092，均保持运行，本工程不重启它们。

启动顺序：eureka → product → order → gateway，逐个等端口就绪。

| 脚本 | 作用 |
| --- | --- |
| `start-oms.ps1` | 按序启动四个 OMS 服务，等端口就绪，PID 写入 `logs\oms\oms-pids.txt` |
| `stop-oms.ps1` | 按 PID 与命令行精确停止 OMS 进程，并逐个确认 8762/8090/8091/8092 释放 |
| `scripts\start-wms-fixture.ps1` | 只读使用 `D:\wms` 现有 jar 启动联调必需的 WMS 服务，日志落 `logs\wms` |
| `scripts\stop-wms-fixture.ps1` | 停止本次启动的 WMS 进程，确认 8761/8081–8089 释放 |
| `scripts\seed-wms-stock.ps1` | 经 WMS 公开入库 API 铺 SKU-001 库存（测试夹具，非 OMS 业务依赖） |
| `scripts\acceptance-smoke.ps1` | 自动化冒烟：注册列表、商品、下单、幂等、对账 |

构建：在 `D:\oms-new` 执行 `mvn -B -DskipTests clean package`。

## 5. 配置项默认值与含义

order-service `application.yml`：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `oms.wms.base-url` | `http://127.0.0.1:8089` | WMS integration 对接入口，OMS 只通过 HTTP 调它 |
| `oms.wms.connect-timeout-ms` | 3000 | WMS 连接超时，显式配置，不使用框架默认值 |
| `oms.wms.read-timeout-ms` | 10000 | WMS 读超时 |
| `oms.product.base-url` | `http://127.0.0.1:8090` | OMS 商品服务地址，下单前校验与计价 |
| `oms.product.connect-timeout-ms` | 3000 | 商品服务连接超时 |
| `oms.product.read-timeout-ms` | 5000 | 商品服务读超时 |
| `oms.push.scan-interval-ms` | 2000 | 补偿任务扫描间隔毫秒 |
| `oms.push.max-retry` | 20 | 下发任务最大重试次数（达到后保留 FAILED，交对账/人工介入） |
| `spring.kafka.consumer.group-id` | `oms-order-service` | 状态事件消费组 |

商品服务与注册地址：各服务 `eureka.client.service-url.defaultZone` 指向 `http://127.0.0.1:8762/eureka/`，实例固定注册到 `127.0.0.1`（`prefer-ip-address: true`），避免主机名解析到局域网 IP 导致网关转发失败。

## 6. 对外接口

商品服务 8090：

- `POST /api/products/upsert` 新增或覆盖商品。
- `GET /api/products/{omsSkuCode}` 按销售编码查询。
- `GET /api/products/list` 全量列表。

订单服务 8091：

- `POST /api/orders` 下单；body：`orderNo`、`buyer`、`remark`、`items[{omsSkuCode, qty}]`。
- `GET /api/orders/{orderNo}` 订单详情，含明细与回传事件流水。
- `POST /api/orders/cancel` 取消；body：`orderNo`、`reason`。
- `GET /api/orders/reconcile` 对账，输出差异清单。

网关 8092 路由：`/order/**` → `lb://oms-order-service`（重写为 `/api/orders/**`），`/product/**` → `lb://oms-product-service`（重写为 `/api/products/**`）。

## 7. 与 WMS 的契约说明

事实依据见 `docs\wms-contract-facts.md`（独立核验产物，只读引用，本工程不修改该文件）。OMS 只调用 integration 的两个 POST：

1. 建单 `POST /integration/oms/outbound/order`
   - body：`omsOrderNo`（必填，WMS 幂等键）、`warehouseCode`（必填）、`remark`（可空）、`items`（数组）。
   - item：`skuCode`（必填）、`locationCode`（必填，硬约束）、`batchNo`（可空）、`qty`（BigDecimal，必填且大于 0）。
   - 成功 `code=200`，`data` 为 WMS 出库单号字符串；订单类型由 WMS 固定为 `SALE_OUT`，OMS 不传类型。
2. 取消 `POST /integration/oms/outbound/cancel`
   - body：`omsOrderNo`、`wmsOutboundNo`、`reason`；前两者不能同时为空。

状态事件：Kafka topic `wms-order-status`，消息 key 为 WMS 出库单号，payload 为 `{orderNo, status, message}`，payload 不含 OMS 单号。OMS 靠本地保存的 `wms_outbound_no` 反查订单。

## 8. 契约差异记录（以 WMS 源码为准）

以下条目已对照 `D:\wms\wms-backend` 源码逐条核实，并在 OMS 实现中对齐：

1. **明细非空校验位置**：WMS 的 `items` 只有 `@NotNull`，没有 `@Size(min=1)`；空数组能通过 Bean Validation，在下游才被 400 拒绝。因此 OMS 在 `OrderCreateRequest` 用 `@NotEmpty` 在入口就拒绝空明细，避免拿到语义模糊的下游错误。
2. **409 的例外情形**：重复推送通常返回 409；但如果该单上次登记状态是 `FAILED`（上次建单失败），同单号允许重试，WMS 会把登记重置为 `RECEIVED` 继续建单并返回 200 与新单号。OMS 下发处理区分 400/409/503 三类：400 不可重试，409 区分「本地已有单号（重复推送，保留既有结果）」与「本地无单号（CONFLICT 待人工）」，503 与网络异常按退避补偿重试。
3. **数量类型**：`qty` 是 BigDecimal，下界 `0.000001`（`@DecimalMin`），不是整数。OMS 明细数量字段同样用 BigDecimal，避免整数截断。
4. **成败判定口径**：WMS HTTP 层对业务错误也返回 HTTP 200，错误只写在 body 的 `code` 字段。OMS 客户端解析 body.code，不依据 HTTP 状态码判定成败；取消与建单的拒绝码和原文 msg 原样透传，不归一化。
5. **无出库单查询接口**：integration 只提供上述两个 POST，没有任何按单号查询单据的 GET；WMS 的回调地址配置默认空，且属于 WMS 配置，禁止修改。因此 OMS 不具备「按 OMS 单号反查 WMS 单据」的能力；崩溃后若本地未存 WMS 单号，只能进入 CONFLICT 待人工，不能换号重试。
6. **取消拦截边界**：WMS 取消只拦截 `FINISHED`（400 已完成不能取消）与 `PICKED` 且存在拣货明细（409 已拣货不可直接取消，请走反向入库回补库存）。状态仍为 `PICKING`、已有拣货量但尚未扣减库存时，源码未拦截，允许取消。OMS 实测时以此边界为准。
7. **建单瞬间不发事件**：`wms-order-status` 事件只在取消、拣货确认到 PICKED、出库完成 FINISHED 时产生；`CREATED`、`PICKING`、`CONFIRMING`、`CONFIRM_FAILED` 不上 Kafka。OMS 不依赖事件流还原完整轨迹。

## 9. 未完成与未使用项（如实说明）

- **Redis 未接入**：需求清单包含 Redis，但当前下单、计价、状态回传链路没有缓存需求，模块 pom 未引入 spring-data-redis，配置中也无 Redis 连接项。如后续需要热点商品缓存或分布式锁，再行补充。
- **WMS 测试残留单据不可删除**：边界禁止向 WMS 库写数据，联调产生的 `jim_wms_outbound`、`jim_wms_integration` 测试单据无法由 OMS 侧清理，清单见验证报告。
- **事件只覆盖终态/取消**：中间态事件不上游，OMS 本地状态在中间态以本地任务状态为准。
- **网关已保留**：Spring Cloud Gateway 在本工程可正常启动并路由，未因版本冲突移除。
