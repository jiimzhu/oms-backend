# WMS 对外提供给 OMS 的契约事实表与联调操作手册

> 核验方式：只读核验。未启动任何 WMS 服务、未占用 8761 与 8081–8089 端口、未执行 mvn 启动命令、未启动 Kafka/MySQL/Redis 客户端进程。
> 证据来源：`D:\wms\wms-backend` 源码、Flyway 迁移脚本、本机 MySQL `information_schema` 只读元数据查询、Kafka 元数据只读查询（`kafka-topics --describe/--list`）。
> 标注约定：
> - 【源码确认】：直接读到的代码、脚本或库表元数据结论。
> - 【推断】：由 Spring Boot 默认行为、配置默认值或代码路径推断，已注明推断依据与不确定面。
> - 路径均相对于仓库根目录 `D:\wms\wms-backend`；行号为本次实际读取到的行号。
> - 纪律：`D:\wms` 下文件未做任何修改；本文件是本次唯一交付物。

## 0. 通用口径与服务拓扑

### 0.1 服务端口与数据库（【源码确认】，各服务 `src/main/resources/application.yml` 第 2 行与数据源 URL）

| 服务 | 端口 | 数据库（实测 information_schema） |
| --- | --- | --- |
| wms-eureka-server | 8761 | 无业务库 |
| wms-admin-service | 8081 | jim_wms_admin |
| wms-inventory-service | 8082 | jim_wms_inventory |
| wms-inbound-service | 8083 | jim_wms_inbound |
| wms-outbound-service | 8084 | jim_wms_outbound |
| wms-transfer-service | 8085 | jim_wms_transfer |
| wms-stocktake-service | 8086 | jim_wms_stocktake |
| wms-return-service | 8087 | jim_wms_return |
| wms-report-service | 8088 | jim_wms_report |
| wms-integration-service | 8089 | jim_wms_integration |

OMS 对接入口在 integration 服务（8089），控制器类级路径 `/integration/oms`（`wms-integration-service/src/main/java/com/jim/wms/integration/controller/OmsOutboundController.java` 第 31 行）。

### 0.1.1 统一返回体 Result 结构（【源码确认】）

文件：`wms-common/src/main/java/com/jim/wms/common/result/Result.java`

| 字段 | Java 类型 | 含义 | 证据 |
| --- | --- | --- | --- |
| code | Integer | 业务状态码；成功固定 200 | 第 13、19 行 |
| msg | String | 业务消息；成功固定 `ok` | 第 14、20 行 |
| data | 泛型 T | 成功载荷；建单/取消链路分别为 WMS 出库单号或 null | 第 15、17–26 行 |

成功工厂：`Result.success(data)` 置 code 200、msg `ok`（第 17–23 行）；失败工厂 `Result.fail(code, msg)` 不置 data（第 29–34 行）。

### 0.1.2 HTTP 状态码口径（【源码确认】）

全局异常处理器 `wms-common/src/main/java/com/jim/wms/common/exception/GlobalExceptionHandler.java` 直接返回 `Result` 对象，未使用 `ResponseEntity`、未标注 `@ResponseStatus`（全工程 Grep `ResponseEntity|@ResponseStatus|HttpStatus.` 仅在历史文档中命中，源码无使用）。

结论：业务异常与校验失败时 **HTTP 状态码仍为 200**，业务成败只看响应体 `code` 字段。OMS 客户端不能只依据 HTTP 状态码判断成败。

异常映射（`GlobalExceptionHandler.java`）：

| 抛出异常 | 返回 body.code | msg 来源 | 证据 |
| --- | --- | --- | --- |
| BusinessException | 异常自带 code（默认 500） | 异常 message | 第 17–21 行 |
| MethodArgumentNotValidException / BindException | 400 | 第一个字段错误的 defaultMessage；取不到时为 `参数校验失败` | 第 23–33 行 |
| 其余未捕获 Exception | 500 | `系统内部错误: ` + 异常类简单名 | 第 35–39 行 |

BusinessException 定义：`wms-common/src/main/java/com/jim/wms/common/exception/BusinessException.java`，单参构造 code 默认 500（第 13–16 行），双参构造使用传入 code（第 18–21 行）。

---

## 1. 建单接口 `POST /integration/oms/outbound/order`

### 1.1 路由与方法签名（【源码确认】）

- 控制器：`wms-integration-service/src/main/java/com/jim/wms/integration/controller/OmsOutboundController.java`
- 方法：`receiveSalesOrder`，第 43–44 行；`@PostMapping("/outbound/order")`，入参 `@Valid @RequestBody OmsOutboundOrderCmd cmd`，返回 `Result<String>`。
- 完整地址（本机联调）：`http://127.0.0.1:8089/integration/oms/outbound/order`。
- 请求体 DTO：`wms-common/src/main/java/com/jim/wms/common/dto/OmsOutboundOrderCmd.java`。

### 1.2 请求字段表（【源码确认】，注解与消息原文取自 DTO）

顶层字段：

| 字段名 | Java 类型 | 是否必填 | 校验注解与约束 | 含义 | 证据 |
| --- | --- | --- | --- | --- | --- |
| omsOrderNo | String | 必填 | `@NotBlank(message = "omsOrderNo 不能为空")` | OMS 业务单号，同时是 WMS 侧幂等键 | 第 27–29 行 |
| warehouseCode | String | 必填 | `@NotBlank(message = "warehouseCode 不能为空")` | 仓库编码 | 第 31–32 行 |
| remark | String | 可空 | 无注解 | 备注 | 第 34–35 行 |
| items | List&lt;Item&gt; | 必填（不可为 null） | `@Valid` + `@NotNull(message = "明细不能为空")`；**未加 `@NotEmpty`/`@Size`，空列表可通过 Bean Validation** | 出库明细列表 | 第 37–39 行 |

嵌套明细 `Item`（DTO 内部类，第 41–58 行）：

| 字段名 | Java 类型 | 是否必填 | 校验注解与约束 | 含义 | 证据 |
| --- | --- | --- | --- | --- | --- |
| skuCode | String | 必填 | `@NotBlank(message = "skuCode 不能为空")` | 商品编码 | 第 47–48 行 |
| locationCode | String | 必填 | `@NotBlank(message = "locationCode 不能为空")` | 库位编码；OMS 链路必须显式给出 | 第 50–51 行 |
| batchNo | String | 可空 | 无注解 | 批次号；空值落库按 NULL 口径处理 | 第 53 行 |
| qty | BigDecimal | 必填 | `@NotNull` 消息 `qty 不能为空`；`@DecimalMin` 下界 0.000001，消息 `qty 必须大于 0` | 明细计划出库数量，必须大于 0，下界 0.000001 | 第 55–57 行 |

补充说明：

- DTO 上没有 `orderType`、`sourceSystem` 等字段，外部无法通过 body 指定订单类型（类型由服务端固定，见第 4.1 节）。【源码确认】
- 【推断】Spring Boot 默认未关闭 Jackson 未知字段忽略，body 中多出的字段会被忽略；本次未用运行时验证该默认行为，影响面仅为“多传字段不报错”。
- `items` 为空数组时 Bean Validation 放行；到达出库服务 `create` 后被拒，错误为 400 `出库单明细不能为空`（见 2.2 节与第 4.5 节）。【源码确认】
- qty 若传入无法解析为数字的文本，会落到 Jackson 反序列化异常；现有全局处理器未单独处理该异常类型，【推断】会兜底为 500 `系统内部错误: HttpMessageNotReadableException`，本次未运行时验证，OMS 侧应自行保证数值格式。

### 1.3 返回体与 data 含义（【源码确认】）

- 成功：`Result<String>`，`code` 200、`msg` `ok`，`data` 为 WMS 出库单号（`outboundNo`，前缀 `OUT` + 时间戳，生成规则见 `wms-common/src/main/java/com/jim/wms/common/util/IdGenerator.java` 第 26–28 行；调用点 `OmsOutboundController.java` 第 71–74 行）。
- 失败：`data` 为 null，失败原因见 body 的 `code` 与 `msg`（见第 2 节）。
- WMS 出库单号格式：`OUT` 拼接 `System.currentTimeMillis()`，不是雪花长 ID（`IdGenerator.nextNo` 第 27 行）。【源码确认】

---

## 2. 建单接口错误码清单与触发条件

### 2.1 Bean Validation 失败的统一错误结构（【源码确认】）

- 触发：`@Valid` 校验不通过（`OmsOutboundController.java` 第 44 行）。
- 处理：`GlobalExceptionHandler.java` 第 23–33 行；返回 `Result<Void>`，`code` 固定 400，`msg` 取第一个字段错误的 `defaultMessage`，取不到字段错误时为 `参数校验失败`，`data` 为 null。
- 可触发的校验消息原文（DTO 注解）：
  - `omsOrderNo 不能为空`
  - `warehouseCode 不能为空`
  - `明细不能为空`
  - `skuCode 不能为空`
  - `locationCode 不能为空`
  - `qty 不能为空`
  - `qty 必须大于 0`

### 2.2 400 主数据校验分支（【源码确认】）

方法 `validateMasterData`，`OmsOutboundController.java` 第 103–123 行。校验顺序：先仓库，再逐明细校验库位存在性、库位归属仓库、商品存在性。

| 分支 | body.code | 原文 message | 证据 |
| --- | --- | --- | --- |
| 仓库不存在（Feign 无响应、code 非 200 或 data 为空） | 400 | `仓库不存在: ` + warehouseCode | 第 105–107 行 |
| 库位不存在 | 400 | `库位不存在: ` + locationCode | 第 110–112 行 |
| 库位不属于该仓库（库位记录 warehouseCode 非空且与入参仓库不一致） | 400 | `库位不属于该仓库: ` + locationCode | 第 113–117 行 |
| 商品不存在 | 400 | `商品不存在: ` + skuCode | 第 118–121 行 |

主数据查询走 Feign 调 admin 服务：`wms-common/src/main/java/com/jim/wms/common/api/AdminApi.java`，仓库 `GET /internal/admin/warehouse/{code}`（第 18–19 行）、库位 `GET /internal/admin/location/{code}`（第 21–23 行）、商品 `GET /internal/admin/sku/{skuCode}`（第 25–26 行）。

### 2.3 409 幂等冲突（【源码确认】）

- 触发条件：同一 `sourceSystem=OMS` + 同一 `omsOrderNo` 的登记记录已存在，且状态不是 FAILED；或并发插入命中唯一索引兜底。
- 原文 message：`单据已接收，请勿重复推送: ` + externalOrderNo。
- 证据：`wms-integration-service/src/main/java/com/jim/wms/integration/service/ExternalOrderService.java` 第 49 行（已存在非 FAILED 记录）、第 63–66 行（`DuplicateKeyException` 兜底）。
- 例外（允许重试）：上次登记后建单失败、记录状态为 FAILED 时，同单号再次推送不会 409，而是把记录重置为 RECEIVED 后继续建单流程（第 37–47 行）。详见第 3.2 节。

### 2.4 503 出库服务不可用与 FAILED 回写行为（【源码确认】）

调用点：`OmsOutboundController.java` 第 54–68 行。

| 分支 | body.code | 原文 message | 对登记表的动作 | 证据 |
| --- | --- | --- | --- | --- |
| Feign 调用出库服务抛异常（网络/无实例/超时等） | 503 | `出库服务暂时不可用，单据已登记为 FAILED，可稍后重试: ` + 异常类简单名 | 先 `markFailed` 置 FAILED，再抛出 | 第 57–62 行 |
| Feign 返回 null 或 code 为 null | 503 | `出库服务建单失败: 出库服务无响应` | `markFailed` 置 FAILED | 第 63–66 行 |
| Feign 返回 code 非 200 | 透传出库服务 code；出库 code 为 null 时兜底 503 | `出库服务建单失败: ` + 出库 msg；msg 为 null 时用 `出库服务无响应` | `markFailed` 置 FAILED | 第 63–68 行 |

要点：登记记录先落库，建单失败后回写 FAILED；登记与建单不在同一事务（控制器第 53 行注释；`ExternalOrderService` 的注册/回写方法均无 `@Transactional` 注解，【源码确认】）。FAILED 记录允许同单号合法重试（第 3.2 节）。

### 2.5 其他可能返回

- 500：未预期的系统异常，`系统内部错误: ` + 异常类简单名（`GlobalExceptionHandler.java` 第 35–39 行）。【源码确认】
- 出库服务内部抛出的业务码（如出库明细 404、库存不足 400）经 Feign 返回后，由 2.4 节的非 200 分支透传 code 并加前缀 `出库服务建单失败: `，同时登记表置 FAILED。【源码确认】

---

## 3. integration 侧内部行为

### 3.1 外部单据登记表名与唯一索引（【源码确认】，库表元数据实测）

- 实体：`wms-integration-service/src/main/java/com/jim/wms/integration/entity/ExternalOrder.java`，`@TableName("wms_external_order")`（第 15 行）。
- 建表脚本：`wms-integration-service/src/main/resources/db/migration/V1__init_schema.sql` 第 5–16 行；出库单号列由 V2 脚本补充：`wms-integration-service/src/main/resources/db/migration/V2__external_order_outbound_no.sql` 第 6–7 行。
- 唯一索引：`uk_external_order (source_system, external_order_no)`（V1 第 15 行；本机 `information_schema.STATISTICS` 实测该索引 `NON_UNIQUE=0`，列为 `source_system`、`external_order_no`）。
- 实测库表：`jim_wms_integration.wms_external_order`（information_schema 查询确认）。

表字段（实体 + 脚本）：

| 字段（Java 名 / 列名） | 含义 | 证据 |
| --- | --- | --- |
| sourceSystem / source_system | 来源系统，OMS 链路固定 `OMS` | 实体第 22 行；控制器第 35 行 |
| externalOrderNo / external_order_no | 外部单号，OMS 链路即 omsOrderNo | 实体第 24 行 |
| outboundNo / outbound_no | 建单成功后回填的 WMS 出库单号 | 实体第 27 行；V2 第 7 行 |
| businessType / business_type | 业务类型，OMS 链路固定 `SALE_OUT` | 实体第 30 行；控制器第 36 行 |
| warehouseCode / warehouse_code | 仓库编码 | 实体第 32 行 |
| status / status | 登记状态，代码实际使用 RECEIVED / FAILED | 实体第 35 行；服务第 26–27 行 |
| rawJson / raw_json | 原始报文 JSON，便于排查 | 实体第 38 行；控制器第 49 行 |

### 3.2 登记表状态机与重试语义（【源码确认】）

实现：`ExternalOrderService.java`。

- 状态常量：`STATUS_RECEIVED` 取值 `RECEIVED`（第 26 行）；`STATUS_FAILED` 取值 `FAILED`（第 27 行）。
- `register` 流程（第 31–67 行）：
  1. 按 `sourceSystem + externalOrderNo` 查询现有记录（第 33–35 行）。
  2. 记录已存在且状态为 FAILED：视为上次建单失败、本地未产生有效出库单，允许同单号重试——更新 businessType、warehouseCode、rawJson，状态重置为 RECEIVED，返回继续建单（第 38–47 行）。
  3. 记录已存在且非 FAILED：抛 409 `单据已接收，请勿重复推送: ` + 单号（第 49 行）。
  4. 无记录则插入 RECEIVED；并发下唯一索引冲突抛 `DuplicateKeyException`，兜底转 409 同文案（第 61–66 行）。
- `markFailed`（第 72–83 行）：按来源 + 单号查记录，置 FAILED 并更新时间；记录不存在只记 WARN 日志，不抛异常，避免掩盖原始建单错误。
- `markOutboundNo`（第 110–119 行）：建单成功后回填 outboundNo；记录不存在只记日志，不抛异常。
- 补充：实体注释与建表脚本注释里还列出 `PICKING / PARTIAL / FINISHED / CANCELLED` 等状态（实体第 34 行；V1 第 11 行），但 OMS 链路代码只写入 RECEIVED/FAILED；`updateStatus` 方法（第 85–95 行）全工程 Grep 无调用方。【源码确认】推断：其余状态值是旧单体遗留注释，当前拆分后未使用。

### 3.3 Feign 调用出库服务与回填（【源码确认】）

- Feign 契约：`wms-common/src/main/java/com/jim/wms/common/api/OutboundApi.java`，`@FeignClient` 名称 `wms-outbound-service`，路径 `/internal/outbound`（第 14 行）。销售建单 `POST /sales-order` 返回 `Result<String>`（第 26–27 行）；取消 `POST /cancel` 返回 `Result<Void>`（第 22–23 行）。
- 建单调用与失败处理：见 2.4 节（`OmsOutboundController.java` 第 54–68 行）。
- 回填：出库服务返回的 `data`（WMS 出库单号）写入登记表 `outbound_no`，并作为接口响应 `data` 返回（第 71–74 行）。
- Feign 超时：connect 3000ms、read 10000ms（`wms-integration-service/src/main/resources/application.yml` 第 37–39 行）。【源码确认】
- 事务边界：登记插入、Feign 建单、回填出库单号三者不在同一事务；建单失败以 FAILED 回写兜底，不产生数据库回滚联动。【源码确认，依据控制器第 53 行注释与服务方法无事务注解】

---

## 4. 出库服务建单真实行为

实现：`wms-outbound-service/src/main/java/com/jim/wms/outbound/service/impl/OutboundServiceImpl.java`，`createSalesOrder` 第 261–284 行。

### 4.1 订单类型、来源系统与 sourceNo（【源码确认】）

| 项 | 落库值 | 说明 | 证据 |
| --- | --- | --- | --- |
| orderType | 固定 `SALE_OUT` | 不接受外部传入；DTO 无该字段 | 第 264 行 |
| sourceSystem | 固定 `OMS` | 与控制器常量一致 | 第 265 行；控制器第 35 行 |
| sourceNo | 入参 `omsOrderNo` | 存于出库单 `source_no`，用于后续按来源单号反查与回调 | 第 266 行 |
| warehouseCode | 入参 `warehouseCode` | 原样落库 | 第 267 行 |

幂等补充：出库单表 `wms_outbound_order` 上 `(source_system, source_no)` 仅为普通索引 `idx_source_no`，不是唯一索引（`wms-outbound-service/src/main/resources/db/migration/V1__init_schema.sql` 第 17 行；本机 information_schema 实测 `NON_UNIQUE=1`）。出库侧不按 sourceNo 做唯一幂等；重复建单的拦截完全依赖 integration 登记表（第 3.2 节）。【源码确认】

### 4.2 明细库位解析规则（【源码确认】）

OMS 销售出库路径：

- 明细逐行复制 skuCode、locationCode、batchNo、planQty（`OutboundServiceImpl.java` 第 271–278 行）。
- `locationCode` 在 DTO 层 `@NotBlank`，且 integration 主数据阶段已校验库位存在并归属该仓库（第 2.2 节）；出库服务不再做库存维度的库位推断。
- 同一 SKU 不同库位必须各占一行；重复维度由数据库唯一键 `uk_item_dim` 兜底（第 269 行注释；索引见 4.4 节）。

按库存推断库位的逻辑只作用于“明细本身无库位”的 CTM 补货单（`OutboundServiceImpl.java` 第 128–132 行、第 780–802 行），OMS 链路不会走到：

- 库存查询返回非 200 或 data 为空：400 `无法解析拣货库位，请指定 locationCode: ` + skuCode（第 782–784 行）。
- 无可用库存（qty 不大于 0，批次不匹配）：400 `无可用库存库位，请指定 locationCode: ` + skuCode（第 786–794 行）。
- 跨多库位/批次无法判定：400 `该 SKU 存在多个可用库位，请指定 locationCode 与 batchNo 后重试；候选: ` + 候选清单（第 795–800 行）。

### 4.3 库存不足或无库存权重时的实际表现（【源码确认】）

- 建单阶段不查询库存台账、不冻结库存、不校验可用量：`create` 方法第 92–108 行无任何库存 Feign 调用；冒烟脚本结论亦注明“建单不扣库存”（`D:\wms\demo-smoke.ps1` 第 91 行）。
- 库存校验发生在出库确认扣减阶段：任务逐条调库存服务 `changeStock`（`OutboundServiceImpl.java` 第 497–523 行）。库存不足由库存服务拒绝，原文 400 模板为 `库存不足, skuCode:<skuCode>, 现有:<before>, 需要:<abs>`（`wms-inventory-service/src/main/java/com/jim/wms/inventory/service/impl/InventoryServiceImpl.java` 第 70–73 行）。
- 出库侧收到非 200：任务置 FAILED，回写 lastError 与 next_retry_time（`OutboundServiceImpl.java` 第 520–534 行）；单据存在 FAILED 任务时置 `CONFIRM_FAILED`，等待补偿（第 187–189、536–543 行）。
- 补偿节奏：失败任务下次重试间隔 1 分钟（第 55 行 `RETRY_DELAY_MINUTES = 1L`；第 530 行）；任务最大重试 5 次（`wms-outbound-service/src/main/resources/application.yml` 第 91 行 `max-retry: 5`）；任务扫描间隔 30000ms（yml 第 86 行；服务第 299–300 行）。
- 对 OMS 的可见性：库存不足不会在建单响应或确认同步响应中直接透传给 OMS；单据可能停在 CONFIRM_FAILED，且 CONFIRM_FAILED 不产生状态事件（见第 5.3 节）。

“无权重”说明：当前出库分配没有库位/批次权重或优先规则；OMS 指定什么库位就落什么库位，库存侧只按台账现有数量扣减。【源码确认，依据明细原样落库第 274 行与扣减任务按任务行库位/批次下发第 498–508 行】

### 4.4 明细唯一维度（【源码确认】，库表元数据实测）

- 表：`wms_outbound_item`（`wms-outbound-service/.../entity/OutboundOrderItem.java` 第 12 行）。
- 唯一键：`uk_item_dim (outbound_no, sku_code, location_code, batch_key)`，`batch_key` 为生成列 `IFNULL(batch_no, 'NO_BATCH')`（`wms-outbound-service/src/main/resources/db/migration/V2__task_lease_and_item_dim.sql` 第 13–15 行；information_schema 实测索引列顺序一致）。
- 扣减任务幂等键 requestId：`OUT:{outboundNo}:{skuCode}:{locationCode}:{batchNo或NOBATCH}`（`OutboundServiceImpl.java` 第 813–816 行；任务表唯一键 `uk_outbound_task_request(request_id)`，V1 脚本第 46 行）。

### 4.5 初始单据状态与建单瞬间事件（【源码确认】）

- 初始状态：`CREATED`（`OutboundServiceImpl.java` 第 98 行）。
- 建单瞬间不产生 `wms-order-status` 事件：`create` 方法（第 92–108 行）未调用发件箱写入；全出库服务 Grep `outboxWriter.write` 仅命中两处：取消 CANCELLED（第 225 行）、终态 FINISHED/PICKED（第 580 行）。

---

## 5. 状态机与事件机制

### 5.1 出库单全部可能状态取值及含义（【源码确认】）

实体注释：`wms-outbound-service/.../entity/OutboundOrder.java` 第 40–41 行。

| 状态 | 含义 | 进入路径 | 证据 |
| --- | --- | --- | --- |
| CREATED | 已建单，尚未拣货 | 建单初始 | `OutboundServiceImpl.java` 第 98 行 |
| PICKING | 已有拣货登记（拣货量大于 0），尚未完成确认扣减 | pick 成功后置位 | 第 139–141 行 |
| CONFIRMING | 出库确认中：本地事务已落 PENDING 补偿任务，远程扣减在途 | confirm 第一步置位 | 第 159–167 行 |
| PICKED | 终态之一：部分出库（存在明细拣货量未达计划量） | 任务全部 SUCCESS 且未达全量 | 第 568–575 行 |
| FINISHED | 终态之一：全部明细拣货量达到或超过计划量 | 任务全部 SUCCESS 且全量完成 | 第 568–575 行 |
| CANCELLED | 已取消；未成功的扣减任务一并置 CANCELLED | cancel 成功 | 第 210–223 行 |
| CONFIRM_FAILED | 确认存在 FAILED 任务，待任务级补偿或单据补扫 | confirm 后存在失败任务 | 第 187–189、536–543 行 |

补偿任务自身状态（`wms_outbound_stock_task.status`）：PENDING / PROCESSING / SUCCESS / FAILED / CANCELLED（实体 `OutboundStockTask.java` 第 18、41 行；V2 脚本第 3 行注释）。

### 5.2 状态流转触发点（【源码确认】）

| 流转 | 触发点 | 证据 |
| --- | --- | --- |
| CREATED → PICKING | `pick` 成功登记拣货量后置位；FINISHED/CANCELLED 单据禁止继续拣货 | 第 113–142 行（拦截第 114–115 行，置位第 139 行） |
| CREATED / PICKING → CONFIRMING | `confirm` 第一步本地事务置 CONFIRMING 并为已拣明细落 PENDING 任务 | 第 145–167 行 |
| CONFIRMING → FINISHED / PICKED | 该单全部任务 SUCCESS；按是否全量完成判定终态 | 第 181–186、551–591 行 |
| CONFIRMING → CONFIRM_FAILED | 存在 FAILED 任务 | 第 187–189、536–543 行 |
| 保持 CONFIRMING | 任务仅 PENDING/PROCESSING 在途或被别实例抢走 | 第 190–192 行 |
| 非 FINISHED/PICKED → CANCELLED | `cancel` 成功；未 SUCCESS 的任务置 CANCELLED | 第 197–223 行 |
| 终态守卫 | 已在 FINISHED/PICKED 不再重复写单据、发事件、回调 | 第 553–556 行 |

`pick` 其他拦截：拣货量不大于 0 → 400 `拣货数量必须大于 0`（第 117–118 行）；累计拣货超计划 → 400 `拣货数量超过计划数量`（第 122–125 行）。
`confirm` 其他拦截：状态非 CREATED/PICKING → 400 `当前状态不允许出库确认: ` + 状态（第 147–149 行）；无任何已拣明细 → 400 `尚未拣货，无法出库确认`（第 154–156 行）。

### 5.3 `wms-order-status` 事件：topic / 分区 / key / payload（【源码确认】 + 元数据实测）

- topic 常量：`wms-common/src/main/java/com/jim/wms/common/event/WmsEventTopics.java` 第 15 行，`TOPIC_ORDER_STATUS = "wms-order-status"`。
- 分区数：本机 Kafka 实测 `PartitionCount: 3`、`ReplicationFactor: 1`（`kafka-topics --describe --topic wms-order-status` 只读查询结果）。
- 消息 key：WMS 出库单号 `outboundNo`（`OutboundServiceImpl.java` 第 225、580 行；写入器签名 `OutboxWriter.write(topic, eventKey, payload)`，`wms-common/src/main/java/com/jim/wms/common/outbox/OutboxWriter.java` 第 40 行）。
- payload 字段（`OutboundServiceImpl.java` 第 819–825 行）：

| payload 字段 | 取值 | 说明 |
| --- | --- | --- |
| orderNo | WMS 出库单号 `outboundNo` | 不是 OMS 单号 |
| status | 单据状态（CANCELLED / FINISHED / PICKED） | 与单据当前状态一致 |
| message | 文案；CANCELLED 为取消原因 reason；终态为 `出库完成` 或 `部分出库`；null 归一为空串 | 第 571、823 行 |

- **payload 不包含 omsOrderNo**：`buildOrderStatusPayload` 只放 orderNo/status/message（第 819–825 行）。OMS 单号与 WMS 单号的映射只能依靠建单响应 data，或已配置的 REST 回调（见 5.4 与第 9 节）。
- 事件覆盖范围：仅 CANCELLED 与终态 FINISHED/PICKED 发事件；PICKING、CONFIRMING、CONFIRM_FAILED 不发事件（全服务仅两处 `outboxWriter.write`，第 225、580 行）。【源码确认】

### 5.4 投递机制：发件箱表、Relay 轮询、事务边界、延迟量级（【源码确认】）

发件箱表 `wms_event_outbox`（`wms-outbound-service/src/main/resources/db/migration/V3__event_outbox.sql` 第 3–20 行；实测存在于 `jim_wms_outbound` 库）：

| 列 | 含义 |
| --- | --- |
| event_id | 事件唯一 ID，唯一键 `uk_event_id`（第 18 行） |
| topic | Kafka 主题 |
| event_key | 消息 key（出库单号） |
| payload | JSON 报文（LONGTEXT） |
| status | PENDING / SENDING / SENT / FAILED（第 9 行） |
| retry_count / next_retry_time / last_error | 重试次数、下次重试时间、最近错误 |
| owner / lease_until | 投递认领者与租约 |
| sent_time | 投递成功时间 |

写入与投递：

- 业务事务内写 PENDING 记录，不直接调 Kafka；台账变更与事件记录在同一本地事务提交（`OutboxWriter.java` 第 15–18、40–56 行；调用点事务边界见 `OutboundServiceImpl.java` 第 210–227、573–582 行）。
- 投递由 `OutboxRelay` 事务外完成：`wms-common/src/main/java/com/jim/wms/common/outbox/OutboxRelay.java`。
  - 轮询间隔：`@Scheduled(fixedDelayString = "${wms.outbox.scan-interval-ms:1000}")`，默认 1000ms（第 72–73 行）；outbound 配置 `scan-interval-ms: 1000`（`wms-outbound-service/src/main/resources/application.yml` 第 74–76 行）。
  - 认领：条件 UPDATE 影响 1 行才算抢到，避免多实例重复投递（第 114–118 行）。
  - 发送：`kafkaTemplate.send(topic, key, payload).get(sendTimeoutMs)`，同步等待上限 5000ms（第 124–125 行；`OutboxProperties.java` 第 29 行）。
  - 失败处理：累加 retry_count、记 last_error、按 backoff 顺延 next_retry_time；达到 max-retry 不再自动重试（第 136–153 行；`OutboxProperties.java` 第 26、32 行；yml 第 78、80 行：max-retry 20、backoff 5000ms）。
  - SENDING 崩溃回收：租约 60000ms，超时重置回 PENDING（`OutboxProperties.java` 第 35 行；`OutboxRelay.java` 第 32–33、89–93 行）。
- 生产者配置：bootstrap `127.0.0.1:9092`，acks `all`，retries 3，字符串序列化（outbound yml 第 29–37 行）。

延迟量级判断：

- 正常路径：业务事务提交后由下一轮 Relay 扫描投递，量级在 1 秒以内（依据 1000ms 扫描间隔，【推断】，未做运行时压测）。
- Kafka 发送失败：按 5000ms 退避逐次重试，最多 20 次；失败期间事件停留在发件箱 FAILED/PENDING，不会永久丢失，但延迟随重试次数放大。
- 投递线程崩溃：SENDING 记录租约 60s 后被回收重投。

### 5.5 WMS 主动通知 OMS 的 REST 通道（与 Kafka 并列的第二条通道，【源码确认】）

- outbound 终态/取消后调 `notifySource` → OMS 分支 `notifyOms`（`OutboundServiceImpl.java` 第 632–653、659–681 行），经 Feign `IntegrationApi.notifyOmsStatus`（`wms-common/.../api/IntegrationApi.java` 第 26–27 行）到 integration 内部接口 `POST /internal/integration/oms/status`（`InternalIntegrationController.java` 第 51–57 行）。
- 通知报文含 `omsOrderNo / orderNo / status / message`（`OmsStatusNotifyCmd.java` 第 20–28 行；组装点 `OutboundServiceImpl.java` 第 660–665 行）。
- 出站 HTTP 回调由 `wms-integration-service/.../client/OmsCallbackClient.java` 执行：回调地址取 `wms.integration.oms.callback-url`，默认空（第 26–27 行）；未配置只记 INFO 跳过（第 37–40 行）；RestTemplate 连接/读超时各 3000ms（第 29–34 行）；回调失败只记 WARN，不阻断上游、不重试（第 48–54 行）。

---

## 6. 取消接口 `POST /integration/oms/outbound/cancel`

### 6.1 字段与校验（【源码确认】）

- 控制器：`OmsOutboundController.java` 第 77–78 行，`@PostMapping("/outbound/cancel")`，入参 `@RequestBody OmsOutboundCancelCmd cmd`（**未加 `@Valid`**），返回 `Result<Void>`。
- DTO：`wms-common/src/main/java/com/jim/wms/common/dto/OmsOutboundCancelCmd.java`。

| 字段名 | Java 类型 | 是否必填 | 约束 | 含义 | 证据 |
| --- | --- | --- | --- | --- | --- |
| omsOrderNo | String | 与 wmsOutboundNo 二选一 | 无注解；二者不能同时为空 | OMS 业务单号 | 第 20–21 行；控制器第 127–131 行 |
| wmsOutboundNo | String | 与 omsOrderNo 二选一 | 无注解；二者不能同时为空 | WMS 出库单号；优先使用 | 第 23–24 行；控制器第 132–134 行 |
| reason | String | 可空 | 无注解 | 取消原因；落到出库单事件 message 与回调 message | 第 26 行；`OutboundServiceImpl.java` 第 197、226 行 |

单号解析逻辑（`OmsOutboundController.java` 第 126–143 行）：优先使用入参 `wmsOutboundNo`（trim 后返回）；未给 wmsOutboundNo 时按 `omsOrderNo` 查登记表回填的 `outbound_no`。

### 6.2 四类错误原文（【源码确认】）

| 类别 | body.code | 原文 message | 触发条件 | 证据 |
| --- | --- | --- | --- | --- |
| 400 参数错误 | 400 | `omsOrderNo 与 wmsOutboundNo 不能同时为空` | 两个单号均为 null 或空白 | 控制器第 127–131 行 |
| 404 外部单据不存在 | 404 | `外部单据不存在: ` + omsOrderNo | 仅给 omsOrderNo，登记表按 OMS + 单号查不到记录 | 控制器第 135–138 行 |
| 400 尚未生成出库单号 | 400 | `单据尚未生成出库单号: ` + omsOrderNo | 登记表存在，但 outbound_no 为空 | 控制器第 139–141 行 |
| 409 已拣货不可取消 | 409 | `已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存` | 出库单状态为 PICKED 且存在 pickedQty 大于 0 的明细；由出库服务抛出，integration 原样透传 | 出库服务 `OutboundServiceImpl.java` 第 203–207 行；透传控制器第 96–98 行 |

补充拦截（出库服务内部，OMS 经透传可见）：

- FINISHED 状态取消：400 `已完成的单据不能取消`（`OutboundServiceImpl.java` 第 199–201 行）。
- 内部取消指令缺 wmsOutboundNo：400 `wmsOutboundNo 不能为空`（第 287–291 行；OMS 入口一般不直接触发）。
- 出库单不存在：404 `出库单不存在: ` + outboundNo（第 723–729 行）。

### 6.3 FINISHED 行为与 integration 对 outbound 返回码的透传关系（【源码确认】）

- FINISHED 单据：在进入事务判断前直接抛 400，不写库、不发取消事件（`OutboundServiceImpl.java` 第 199–201 行）。
- Feign 调用取消异常：integration 抛 503 `出库服务暂时不可用: ` + 异常类简单名（`OmsOutboundController.java` 第 88–91 行）。
- 出库返回 null 或 code 为 null：503 `出库服务无响应`（第 93–95 行）。
- 出库返回非 200：**code 与 msg 原样透传**，不归一成 502/500；msg 为空时兜底 `出库取消失败`（第 96–98 行，注释明确“PICKED 的 409 文案必须落到调用方”）。
- 取消成功：出库服务在事务内置 CANCELLED、取消未成功任务、写 CANCELLED 事件，事务提交后回调来源系统（`OutboundServiceImpl.java` 第 210–229 行）；integration 返回 `Result.success()`（`OmsOutboundController.java` 第 99 行）。
- 取消链路不回写登记表业务状态：控制器未调用 `updateStatus`（全工程该方法无调用方，见 3.2 节）。【源码确认】

---

## 7. 联调夹具手册

> 前置约定：本手册只描述公开 API 调用顺序与入参；不启动服务的操作不在本手册范围。中间件本机地址：MySQL 127.0.0.1:3306、Redis 127.0.0.1:6379、Kafka 127.0.0.1:9092。

### 7.1 预置主数据（【源码确认】，admin 种子脚本）

`wms-admin-service/src/main/resources/db/migration/V2__seed_master_data.sql`：

- 仓库：`DC01`（上海主仓）、`DC02`（广州主仓）（第 2–4 行）。
- DC01 库位：`DC01-RCV-01`、`DC01-A-01-01`、`DC01-A-01-02`、`DC01-P-01`、`DC01-NG-01`（第 12–17 行）。
- 商品：`SKU-001`（测试商品A，批次管理）、`SKU-002`（测试商品B）（第 19–21 行）。

冒烟脚本常量取自上述主数据：SKU `SKU-001`、仓库 `DC01`、库位 `DC01-A-01-01`、批次 `B-DEMO`（`D:\wms\demo-smoke.ps1` 第 7–10 行）。

### 7.2 铺库存：入库上架四步（参考 `D:\wms\demo-smoke.ps1` 第 57–78 行，未修改该脚本）

入库服务端口 8083，控制器 `wms-inbound-service/.../controller/InboundController.java`。

第 1 步：建入库单 `POST http://127.0.0.1:8083/api/inbound/order`（第 25–28 行）

```json
{
  "inboundType": "PURCHASE",
  "sourceSystem": "DEMO",
  "sourceNo": "DEMO-PO-001",
  "warehouseCode": "DC01",
  "items": [
    { "skuCode": "SKU-001", "batchNo": "B-DEMO", "expectQty": 20 }
  ]
}
```

- 返回 `data.inboundNo`（前缀 `IN` + 时间戳，`InboundServiceImpl.java` 第 49 行）。
- 明细为空数组被拒：400 `入库单明细不能为空`（第 46–47 行）。

第 2 步：收货 `POST /api/inbound/receive`（第 30–32 行）

```json
{ "inboundNo": "<上一步入库单号>", "skuCode": "SKU-001", "qty": 20 }
```

- 前置：单据与明细存在；累计实收不超预期，否则 400 `实收数量超过预期数量`（`InboundServiceImpl.java` 第 67–72 行）。
- 收货后单据状态 `RECEIVED`（第 75 行）。

第 3 步：质检 `POST /api/inbound/qc`（第 36–38 行）

```json
{ "inboundNo": "<入库单号>", "skuCode": "SKU-001", "result": "PASS" }
```

- 结论仅允许 `PASS` / `REJECT`，否则 400 `质检结论只能是 PASS 或 REJECT`（`InboundServiceImpl.java` 第 84–85 行）。

第 4 步：上架 `POST /api/inbound/putaway`（第 42–45 行）

```json
{ "inboundNo": "<入库单号>", "skuCode": "SKU-001", "locationCode": "DC01-A-01-01", "qty": 20 }
```

- 前置：QC 非 REJECT（否则 400 `质检不合格，不允许正常上架到可售库位`，第 96–97 行）；累计上架不超实收（否则 400 `上架数量超过实收数量`，第 99–100 行）。
- 上架成功远程加库存；全部明细上架完成后单据 `FINISHED` 并发入库完成事件（第 137–153 行）。

### 7.3 出库作业接口入参与状态变化（outbound 8084）

控制器：`wms-outbound-service/.../controller/OutboundController.java`，类级路径 `/api/outbound`（第 19 行）。

#### 7.3.1 拣货 `POST /api/outbound/pick`（第 30–35 行）

请求体 `PickRequest`（第 50–56 行）：

```json
{ "outboundNo": "<WMS出库单号>", "skuCode": "SKU-001", "locationCode": "DC01-A-01-01", "batchNo": "B-DEMO", "qty": 3 }
```

- 前置状态：单据存在；状态非 FINISHED/CANCELLED（否则 400 `单据已结束，无法继续拣货`，`OutboundServiceImpl.java` 第 113–115 行）；qty 大于 0（第 117–118 行）；累计拣货不超计划（第 122–125 行）。
- 明细定位：给了 locationCode 按 SKU+库位+批次等值匹配；未给库位按 SKU（+批次）宽松匹配，多行候选报 400 并附候选（第 739–774 行）。
- 调用后单据状态置 `PICKING`（第 139 行）；拣货本身不扣库存。

#### 7.3.2 出库确认 `POST /api/outbound/confirm`（第 37–41 行）

请求体 `OrderNoRequest`（第 59–61 行）：

```json
{ "outboundNo": "<WMS出库单号>" }
```

- 前置状态：状态为 CREATED 或 PICKING（否则 400 `当前状态不允许出库确认: ` + 状态，`OutboundServiceImpl.java` 第 147–149 行）；至少一行 pickedQty 大于 0（否则 400 `尚未拣货，无法出库确认`，第 154–156 行）。
- 状态变化：先置 `CONFIRMING` 并落 PENDING 任务（第 159–167 行）；任务全部 SUCCESS → `FINISHED`（全量）或 `PICKED`（部分）；存在 FAILED → `CONFIRM_FAILED`；任务在途 → 保持 `CONFIRMING`（第 181–192 行）。
- 真正库存扣减发生在本阶段；库存不足见 4.3 节。

#### 7.3.3 取消 `POST /api/outbound/cancel`（第 43–47 行）

请求体 `CancelRequest`（第 64–67 行）：

```json
{ "outboundNo": "<WMS出库单号>", "reason": "现场取消" }
```

- 规则见第 6.2、6.3 节；成功后置 `CANCELLED` 并发 CANCELLED 事件。

### 7.4 用 WMS 公开 API 判断单据当前状态的可用手段与局限

【源码确认】出库域与 integration 域没有任何 `@GetMapping`；全工程 GET 接口只分布在 admin 主数据、inventory 库存、report 报表。因此**不存在按单号查询出库单状态的公开 API**。联调时可观测手段：

1. Kafka 事件 `wms-order-status`：只能看到 CANCELLED / FINISHED / PICKED 三类终态与取消态，看不到 PICKING、CONFIRMING、CONFIRM_FAILED。
2. 库存汇总报表：`GET http://127.0.0.1:8088/api/report/stock-summary?warehouseCode=DC01`（`wms-report-service/.../controller/ReportController.java` 第 37–38 行）；返回字段 `warehouseCode / skuCode / onHandQty / freezeQty / availableQty`（`StockSummaryVO.java` 第 15–21 行）。只能间接对账库存总量，不能回答单据状态。
3. 库存明细查询：`GET /api/inventory/query?warehouseCode=DC01`，另带查询参数 `skuCode` 取值 `SKU-001`（`wms-inventory-service/.../controller/InventoryController.java` 第 28–32 行），返回 `InventoryView` 列表（`wms-common/.../dto/InventoryView.java` 第 15–32 行）。
4. 主数据核对：`GET http://127.0.0.1:8081/api/master/locations?warehouseCode=DC01`、`/api/master/skus` 等（`wms-admin-service/.../controller/MasterDataController.java` 第 44–51 行）。

### 7.5 Kafka 读取指定分区与 offset 的命令行（本机可直接复制执行）

环境：Kafka 4.4 KRaft，脚本目录 `C:\Users\华为\kafka\kafka_2.13-4.4.0\bin\windows`，broker `127.0.0.1:9092`。

先定义脚本目录（PowerShell）：

```powershell
$K = "C:\Users\华为\kafka\kafka_2.13-4.4.0\bin\windows"
```

查看 topic 分区与副本：

```powershell
& "$K\kafka-topics.bat" --bootstrap-server 127.0.0.1:9092 --describe --topic wms-order-status
```

查看各分区当前最新 offset：

```powershell
& "$K\kafka-get-offsets.bat" --bootstrap-server 127.0.0.1:9092 --topic wms-order-status
```

读取指定分区、指定 offset 起的消息（打印 key 与 value，超时 15 秒退出）：

```powershell
& "$K\kafka-console-consumer.bat" --bootstrap-server 127.0.0.1:9092 --topic wms-order-status --partition 0 --offset 123 `
  --property print.key=true `
  --property key.separator="|" --timeout-ms 15000
```

从指定分区头部开始全量读取：

```powershell
& "$K\kafka-console-consumer.bat" --bootstrap-server 127.0.0.1:9092 --topic wms-order-status --partition 1 --from-beginning `
  --property print.key=true `
  --property key.separator="|" --timeout-ms 15000
```

不带分区条件订阅全量（按 key 哈希分区消费）：

```powershell
& "$K\kafka-console-consumer.bat" --bootstrap-server 127.0.0.1:9092 --topic wms-order-status `
  --property print.key=true `
  --property key.separator="|" --timeout-ms 15000
```

使用注意：

- value 是 JSON 字符串，字段为 `orderNo / status / message`；key 是 WMS 出库单号。
- 带 `--partition` 与 `--offset` 时不要同时带 `--group`（控制台消费者按分区指定位移消费，组订阅模式不适用该组合）。【推断，依据 Kafka 控制台消费者常规语义；如本机版本报参数冲突，去掉 `--group` 即可】
- 事件无 omsOrderNo，跨分区按单号归并时只能用 key（WMS 出库单号）关联。

---

## 8. 给 OMS 的硬约束清单（【源码确认】，逐条可落地）

1. 每条明细必须显式携带 `locationCode`：DTO `@NotBlank`，且 integration 校验库位存在并归属该仓库；OMS 不能依赖 WMS 自动分配库位。
2. `batchNo` 可空；`qty` 必填且必须大于 0，数值下界 0.000001，类型为十进制数。
3. `omsOrderNo` 是幂等键，作用域为 `sourceSystem=OMS + omsOrderNo`；重复推送不是“幂等返回原单号”，非 FAILED 记录直接 409 拒绝。
4. 只有上一次登记为 FAILED 的同单号允许重试；重试会重新走建单流程并生成新的 WMS 出库单号，OMS 不能假定重试后单号不变。
5. 订单类型由 WMS 固定 `SALE_OUT`，来源系统固定 `OMS`，body 中传其他类型不会被采纳。
6. 建单成功响应 `data` 才是 WMS 出库单号；OMS 必须持久化 `omsOrderNo ↔ wmsOutboundNo` 映射，因为 Kafka 事件不含 OMS 单号。
7. 业务成败看响应体 `code`，不看 HTTP 状态码；HTTP 恒为 200。
8. 建单不校验、不冻结库存；库存不足在确认扣减阶段才暴露，单据可能停在 CONFIRM_FAILED，OMS 不能以建单成功作为可出库承诺。
9. 取消 FINISHED 单据返回 400；PICKED 且已拣货的单据返回 409，必须走反向入库（退货/调拨退回）回补库存，不能要求 WMS 直接取消冲减。
10. 取消时 `omsOrderNo` 与 `wmsOutboundNo` 至少给一个；只给 omsOrderNo 时依赖 WMS 登记表反查出库单号，登记记录不存在或未回填单号会分别得到 404 / 400。
11. 事件只覆盖 CANCELLED、FINISHED、PICKED；中间态（PICKING、CONFIRMING）与失败态（CONFIRM_FAILED）不上 Kafka，OMS 不能依赖事件流还原完整状态轨迹。
12. REST 状态回调默认未配置地址；若 OMS 不提供回调地址且不订阅 Kafka，将收不到任何状态通知。

---

## 9. 契约缺口清单与对 OMS 设计的影响面

### 9.1 缺出库单/外部单据查询接口（【源码确认】）

- 现状：integration 对外仅两个 OMS POST（建单、取消），无任何 GET；outbound 公开接口为 `/api/outbound/order|pick|confirm|cancel` 四个 POST，无按单号查状态接口（Grep `@GetMapping` 出库域零命中）。
- 影响面：
  - OMS 崩溃重启后若本地未存 WMS 单号，重复推送会被 409 拒绝，且没有公开接口反查原单号；取消只能凭 omsOrderNo 由 WMS 内部登记表反查，OMS 自身无法核对。
  - 状态对账缺少主动查询入口，只能被动等事件/回调；事件又不含中间态（见 9.3），漏消息后难以补全。
  - 建议 OMS 侧持久化建单响应的 wmsOutboundNo，并在本地维护状态机，不要设计“调 WMS 查询接口对账”的流程。

### 9.2 回调地址默认空，且属于 WMS 侧配置（【源码确认】）

- 现状：`wms.integration.oms.callback-url` 默认空串（`wms-integration-service/src/main/resources/application.yml` 第 67–70 行；`OmsCallbackClient.java` 第 26–27 行）；未配置时只记 INFO 跳过。
- 影响面：
  - OMS 不能自助注册回调地址，需要 WMS 部署方改配置；改配置等于改 WMS 服务配置，涉及双方发布协调。
  - 回调失败只记 WARN，不重试、不阻断单据终态（`OmsCallbackClient.java` 第 51–54 行；`OutboundServiceImpl.java` 第 675–680 行）；OMS 不能把 REST 回调当作可靠通知通道，必须以 Kafka 事件或本地台账为准。

### 9.3 Kafka 事件不含 OMS 单号，且缺中间态/失败态（【源码确认】）

- 现状：payload 仅 `orderNo/status/message`，orderNo 为 WMS 出库单号（第 5.3 节）；PICKING、CONFIRMING、CONFIRM_FAILED 不发事件。
- 影响面：
  - 消费方必须先用建单响应或回调报文建立 `omsOrderNo ↔ orderNo` 映射；只订阅 Kafka 无法把事件归并回 OMS 订单。
  - CONFIRM_FAILED 不发事件，库存不足/扣减失败对 OMS 不可见；OMS 若需要失败告警，需另建对账手段（当前 WMS 未提供）。
  - 部分出库 PICKED 会发事件，但“哪些明细少出、少出多少”不在 payload 中，需结合库存或后续单据核对。

### 9.4 无库存预占与建单前可用性校验（【源码确认】）

- 现状：建单不查库存、不冻结；可用量不足在 confirm 扣减时才由库存服务拒绝，任务 FAILED、单据 CONFIRM_FAILED，自动补偿最多 5 次。
- 影响面：OMS 不能在建单环节承诺库存已锁定；超卖/缺货风险需在 OMS 侧预占或在接单前调库存查询接口（`/api/inventory/query`、`/api/inventory/on-hand`）自行核对。CONFIRM_FAILED 后的人工介入流程 WMS 未提供配套接口。

### 9.5 登记表对 OMS 不可见，取消反查依赖 WMS 内部数据（【源码确认】）

- 现状：`wms_external_order` 是 integration 内部表，无对外查询接口；取消时按 omsOrderNo 反查出库单号的动作发生在 WMS 内部（`OmsOutboundController.java` 第 135–142 行）。
- 影响面：OMS 未保存 wmsOutboundNo 时，取消链路强依赖登记表记录完好；登记记录缺失返回 404，未回填单号返回 400，OMS 需要把这两类错误映射为“需人工核对”。

### 9.6 无部分取消、部分出库明细查询接口（【源码确认】）

- 现状：取消为整单维度；公开 API 不返回明细行计划量/已拣量/已出库量。
- 影响面：OMS 无法通过 WMS 接口获知部分出库（PICKED）的明细缺口，拆单、补差、退货判断需依赖 OMS 本地台账与库存报表间接推算。

### 9.7 未确认项与不确定面

- 【推断】Jackson 对未知字段的忽略行为、类型不匹配反序列化异常的最终归类，未做运行时验证；若 OMS 调试时发现多传字段被拒或数字文本返回 500，以实际响应为准并反馈。
- 【源码确认】登记表注释中的 `PARTIAL` 等状态值当前代码未使用；若旧文档出现该状态，不应作为新契约依据。
- 事件延迟量级为基于扫描间隔的推断值，未在本机压测；跨实例多分区场景下的端到端延迟需联调时实测。

---

## 10. 对给定契约摘要的独立复核

复核对象（原文）：

> 「建单 body 字段：omsOrderNo 必填且为幂等键、warehouseCode 必填、remark 可空、items 非空且每项 skuCode 必填、locationCode 必填、batchNo 可空、qty 必填大于 0；成功返回 code=200 与 data 为 WMS 出库单号；订单类型服务端固定 SALE_OUT；重复推送返回 409；取消 body 为 omsOrderNo/wmsOutboundNo/reason 三者，前两者不能同时为空；PICKED 且已拣货取消返回 409 且必须走反向入库；FINISHED 取消返回 400；wms-order-status 的 payload 为 orderNo/status/message，不含 OMS 单号。」

### 10.1 逐条对照

| # | 摘要说法 | 复核结论 | 源码依据 | 不符/补充 |
| --- | --- | --- | --- | --- |
| 1 | omsOrderNo 必填且为幂等键 | 准确 | DTO `OmsOutboundOrderCmd.java` 第 28–29 行；登记 `OmsOutboundController.java` 第 50–51 行；`ExternalOrderService.java` 第 33–35、49 行 | 幂等作用域是 `sourceSystem=OMS + omsOrderNo`，不是全局单号唯一 |
| 2 | warehouseCode 必填 | 准确 | DTO 第 31–32 行 | 无补充 |
| 3 | remark 可空 | 准确 | DTO 第 34–35 行 | 无补充 |
| 4 | items 非空 | **部分不准确** | DTO 第 37–39 行仅有 `@NotNull` 与 `@Valid`，无 `@NotEmpty`/`@Size` | 空列表能通过 Bean Validation，到出库服务 `create` 才被 400 `出库单明细不能为空` 拒绝（`OutboundServiceImpl.java` 第 93–95 行）。“非空”是下游行为，不是入参校验约束 |
| 5 | 每项 skuCode 必填 | 准确 | DTO 第 47–48 行 | 无补充 |
| 6 | locationCode 必填 | 准确 | DTO 第 50–51 行；integration 主数据校验 `OmsOutboundController.java` 第 109–117 行 | 无补充 |
| 7 | batchNo 可空 | 准确 | DTO 第 53 行 | 空批次落库按 NULL 口径，任务幂等键用 `NOBATCH` 占位（`OutboundServiceImpl.java` 第 58、814 行） |
| 8 | qty 必填大于 0 | 基本准确，需补充 | DTO 第 55–57 行 | 类型为 `BigDecimal`；下界是 `@DecimalMin("0.000001")`，即严格大于 0，最小正值 0.000001，不是简单的大于 0 整数 |
| 9 | 成功返回 code 200 与 data 为 WMS 出库单号 | 准确 | `OmsOutboundController.java` 第 71–74 行；`Result.java` 第 17–23 行 | 补充：HTTP 状态码恒 200，失败时 code 在 body 内；data 为 `OUT` + 时间戳格式单号 |
| 10 | 订单类型服务端固定 SALE_OUT | 准确 | `OutboundServiceImpl.java` 第 264 行 | 来源系统同时固定 `OMS`，sourceNo 存 omsOrderNo（第 265–266 行） |
| 11 | 重复推送返回 409 | 基本准确，有例外 | `ExternalOrderService.java` 第 49、65 行 | 例外：上次登记为 FAILED 的同单号允许重试，重置 RECEIVED 后重新建单并返回 200（第 38–47 行），不会 409。409 文案为 `单据已接收，请勿重复推送: ` + 单号 |
| 12 | 取消 body 为 omsOrderNo/wmsOutboundNo/reason 三者 | 准确 | `OmsOutboundCancelCmd.java` 第 21、24、26 行 | 控制器未加 `@Valid`，reason 可空且无校验 |
| 13 | 前两者不能同时为空 | 准确 | `OmsOutboundController.java` 第 127–131 行，400 `omsOrderNo 与 wmsOutboundNo 不能同时为空` | 只给 omsOrderNo 时内部反查登记表；记录不存在 404、未回填单号 400（第 135–141 行） |
| 14 | PICKED 且已拣货取消返回 409 且必须走反向入库 | 准确 | `OutboundServiceImpl.java` 第 203–207 行，409 `已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存` | 代码条件为 `status = PICKED` 且存在 pickedQty 大于 0 的明细。边界情形：状态仍为 PICKING、已有拣货量但尚未扣减库存时，代码未拦截，允许取消（第 197–208 行仅判 FINISHED 与 PICKED）；建议联调时验证该边界是否符合业务预期 |
| 15 | FINISHED 取消返回 400 | 准确 | `OutboundServiceImpl.java` 第 199–201 行，400 `已完成的单据不能取消` | 该分支在事务之前抛出，不写库、不发事件 |
| 16 | payload 为 orderNo/status/message，不含 OMS 单号 | 准确 | `OutboundServiceImpl.java` 第 819–825 行 | 补充：`orderNo` 是 WMS 出库单号；OMS 单号只出现在 REST 回调报文（`OmsCallbackClient.java` 第 44–47 行），不在 Kafka 事件中 |

### 10.2 摘要遗漏的关键事实（建议补进 OMS 对接文档）

1. 主数据四类 400 校验：仓库不存在、库位不存在、库位不属于该仓库、商品不存在，各自原文 message 见 2.2 节。
2. 出库服务不可用时登记表置 FAILED，503 文案含“单据已登记为 FAILED，可稍后重试”；非 200 返回同样回写 FAILED（2.4 节）。
3. 建单瞬间不发事件；事件仅 CANCELLED、FINISHED、PICKED 三类；CONFIRM_FAILED 不发事件（4.5、5.3 节）。
4. 出库侧 `(source_system, source_no)` 非唯一索引，幂等完全依赖 integration 登记表（4.1 节）。
5. 无出库单查询公开接口；回调地址默认空且需改 WMS 配置（第 9 节）。
6. 取消接口对 outbound 返回码原样透传；Feign 异常与无响应分别为 503（6.3 节）。
