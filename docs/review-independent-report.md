# 独立复核报告（2026-11-18）

复核对象：`D:\oms-new`，对照 `docs\verification-report.md` 的自述结论。复核方式：用交付脚本从冷启动重跑，核心链路全部由本次新建单号（REV-1 ~ REV-4）独立取证；只读访问 WMS 库，未写入、未重新构建 `D:\wms`。

## 0. 复核环境与基线

- 中间件：MySQL 3306、Redis 6379、Kafka 9092 全程在线，未重启。
- 复核启动前端口基线：8761、8762、8081–8089、8090–8092 全部空闲。
- 启动前 OMS 库：`oms_order=0`、`oms_order_item=0`、`oms_push_task=0`、`oms_wms_event=0`、`oms_product=1`（样例商品 OMS-SKU-001）。
- 启动前 WMS 基线：出库单 14、登记记录 14（11 条 OMS 旧残留 + 本次复核前的计数口径，见 §7）。

## 1. 冷启动与注册（任务书一）

### 1.1 启动 WMS 夹具
命令：`.\scripts\start-wms-fixture.ps1`
输出（`logs\review-wms-start.txt`）：
```
已启动 wms-eureka-server PID=26560
已启动 wms-admin-service PID=20488
已启动 wms-inventory-service PID=16808
已启动 wms-inbound-service PID=33624
已启动 wms-outbound-service PID=24492
已启动 wms-integration-service PID=26120
已启动 wms-report-service PID=28032
UP  wms-eureka-server 8761 / wms-admin 8081 / inventory 8082 / inbound 8083 / outbound 8084 / integration 8089 / report 8088
```
铺库存：`.\scripts\seed-wms-stock.ps1`，SKU-001 可用量 9 → 29，入库单 IN1794985268271。
结论：通过。脚本按序拉起并等端口就绪，中文日志无编码报错。

### 1.2 启动 OMS
命令：`.\start-oms.ps1`
输出（`logs\review-oms-start.txt`）：
```
已启动 oms-eureka-server PID=36580
已启动 oms-product-service PID=17096
已启动 oms-order-service PID=23796
已启动 oms-gateway PID=20440
UP  oms-eureka-server 8762 / product 8090 / order 8091 / gateway 8092
```
结论：通过。四服务按 eureka→product→order→gateway 顺序启动，端口等待逻辑生效。

### 1.3 Eureka 注册列表（8762）
命令：`GET http://127.0.0.1:8762/eureka/apps`
实测实例：
```
APP=OMS-ORDER-SERVICE   id=oms-order-service:8091   status=UP  host=127.0.0.1:8091
APP=OMS-PRODUCT-SERVICE  id=oms-product-service:8090 status=UP  host=127.0.0.1:8090
APP=OMS-GATEWAY          id=oms-gateway:8092        status=UP  host=127.0.0.1:8092
```
结论：通过。三业务实例均在 8762 注册为 UP，地址固定 127.0.0.1；eureka 自身按配置不注册。

### 1.4 路由连通
实测 HTTP 状态：
```
http://127.0.0.1:8090/api/products/list            -> 200
http://127.0.0.1:8092/product/list                -> 200
http://127.0.0.1:8091/api/orders/reconcile        -> 200
http://127.0.0.1:8092/order/reconcile             -> 200
```
结论：通过。直连与网关两条路由（/product、/order）均返回 200。

## 2. 核心链路独立复测（任务书二）

### 2.1 商品与下单
- 建商品：`POST 8090/api/products/upsert`，omsSkuCode=REV-SKU-001，price=88.50，映射 SKU-001 / DC01 / DC01-A-01-01 / B-DEMO，返回 code=200，商品 id=2。
- 下单：`POST 8091/api/orders`，orderNo=REV-1，qty=3。返回 code=200，初始 PENDING_PUSH，totalAmount=265.50（88.50×3）。
- 回填轮询：第 0 次轮询即 `WAITING_WMS`，wmsOutboundNo=OUT1794985372695。

只读 SQL 核对（jim_wms_outbound / jim_wms_integration）：
```
source_no=REV-1  source_system=OMS  order_type=SALE_OUT  status=CREATED  outbound_no=OUT1794985372695  warehouse=DC01
登记: external_order_no=REV-1  status=RECEIVED  outbound_no=OUT1794985372695  business_type=SALE_OUT
OMS: REV-1  WAITING_WMS  OUT1794985372695  total=265.50  last_error=NULL
```
结论：通过。WMS 真实生成 SALE_OUT 单据，source_no 等于提交的业务单号，OMS 已回填单号。

### 2.2 拣货、确认到终态
- `POST 8084/api/outbound/pick`：outboundNo=OUT1794985372695，SKU-001，库位 DC01-A-01-01，批次 B-DEMO，qty=3 → code=200。
- `POST 8084/api/outbound/confirm`：同单号 → code=200。
- OMS 轮询：iter=0 即 FINISHED。
结论：通过。两侧最终 FINISHED。

### 2.3 Kafka 直读事件证据（不依赖 OMS 事件表）
命令（java 直调 ConsoleConsumer，绕开 .bat 参数拆分问题）：
`--partition 2 --offset 8 --topic wms-order-status`
直读原始行：
```
OUT1794985372695|{"orderNo":"OUT1794985372695","message":"出库完成","status":"FINISHED"}
```
OMS 本地事件表（UTF-8 直读 SQL）：
```
kafka_partition=2  event_offset=8  message_key=OUT1794985372695  status=FINISHED  message=出库完成
raw_value={"orderNo":"OUT1794985372695","message":"出库完成","status":"FINISHED"}
```
结论：通过。本次所造单据的消息确实存在于分区 2、offset 8，key/orderNo/status 与本地记录一致；中文在 Kafka 控制台为 GBK 解码乱码，但库内 raw_value 为正确 UTF-8。

### 2.4 幂等
- 同一 REV-1 再次下单，返回 body：`code=409`，`msg=业务单号重复，请勿重复提交: REV-1`（响应体按 UTF-8 解码可读；PS 直接打印为 GBK 乱码）。
- 三侧计数：`oms=1  wms_out=1  wms_reg=1`。
结论：通过。重复提交被明确拒绝，OMS/WMS 出库单/WMS 登记各仅一张。

### 2.5 取消链路
**5A 未拣货取消（REV-2 → OUT1794985675866）**
- 下单 qty=2，回填 WAITING_WMS。
- `POST /api/orders/cancel`：code=200；OMS=CANCELLED。
- WMS：`REV-2 CANCELLED OUT1794985675866`。
- 事件：partition=2 offset=9，key=OUT1794985675866，status=CANCELLED，message=`CTM 取消单据: REV-2`。
结论：通过。两侧均到 CANCELLED，并收到 CANCELLED 事件。

**5B 已拣货部分出库取消（REV-3 → OUT1794985711422）**
- 下单 qty=2；pick qty=1；confirm → 两侧 PICKED。
- 发起取消，响应体（RawContentStream 按 UTF-8 解码）：
```
{"code":409,"msg":"已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存","data":null}
code=409  match=True（与任务书原文逐字相等）
```
- 取消后 OMS 状态：PICKED（未被改成 CANCELLED）。WMS：REV-3 PICKED。
结论：通过。409 与 WMS 原文原样透传，拒绝未被吞掉；OMS 状态保持 PICKED。

### 2.6 对账
- 基线：仅一条 EVENT_NO_ORDER，wmsOutboundNo=IN1794985268271（入库完成事件，OMS 无对应订单）。
- 人为制造不一致：把 REV-1 本地状态改为 WAITING_WMS 后跑 `/api/orders/reconcile`：
```
STATUS_MISMATCH | REV-1 | OUT1794985372695 | oms=WAITING_WMS | wms=FINISHED
EVENT_NO_ORDER  |       | IN1794985268271  | oms=             | wms=FINISHED
```
- 改回 FINISHED 后复跑：STATUS_MISMATCH 消失，仅剩 IN... 的 EVENT_NO_ORDER。
结论：通过。对账差异精确到单号与差异类型（STATUS_MISMATCH），还原后差异消失。

### 2.7 崩溃恢复（真实场景）
前置：WMS 出库单 14、登记 14。
1. 停 8089（integration PID 26120），端口释放。
2. 建单 REV-4（qty=1）。失败后快照：
```
OMS: REV-4 FAILED wms_outbound_no=NULL
     last_error=503: 调用 WMS 建单网络异常: ResourceAccessException: Connection refused
任务: REV-4 FAILED retry_count=1 next_retry_time=2026-11-18 15:10:35
WMS: wms_out=0  wms_reg=0
```
3. 强杀 order 进程（8091 PID 23796），端口释放。杀后快照：本地 FAILED/retry=1 仍在，WMS 仍 0；OMS 仅剩 eureka/product/gateway 三个进程。
4. 单独重启 order（PID 29236），8089 仍停。重启后：
```
OMS: REV-4 FAILED NULL  last_error=503 Connection refused
任务: retry_count=2  next_retry_time=2026-11-18 15:12:02
WMS: wms_out=0  wms_reg=0
```
5. 恢复 8089（integration PID 30948）。补偿轮询：iter=0 FAILED，iter=1 FAILED，iter=3 WAITING_WMS，wms=OUT1794985923429。
```
OMS: REV-4 WAITING_WMS OUT1794985923429 last_error=NULL
任务: DONE retry_count=2 last_error=（保留旧错误文本，见 §6 记录）
WMS: REV-4 OMS SALE_OUT CREATED OUT1794985923429
登记: OMS REV-4 RECEIVED OUT1794985923429 SALE_OUT
```
6. 计数：REV-4 三侧 1/1/1；全局 WMS 出库单 15、登记 15（基线 14，仅 +1）。
结论：通过。崩溃后本地订单与任务保留、无静默丢失；WMS 恢复后补偿自动下发成功，仅生成一张 WMS 单、一张本地单，无重复。

## 3. 静态复核（任务书三）

### 3.1 TODO/FIXME/mock 与注释
- 全工程 `*.java` 检索 TODO/FIXME/mock/MOCK/假实现/未实现/not implemented：0 命中。
- 抽查五个关键类，类级 Javadoc 均说明「解决什么问题」：
  - `OrderService`：下单主链路，幂等校验→计价→同事务落订单/明细/任务→事务提交后下发。
  - `PushWorker`：WMS 下发补偿 worker，认领租约、超时/崩溃补偿、400/409/503 分支。
  - `StatusEventConsumer`：消费 wms-order-status，先落事件取证再按出库单号反查推进。
  - `CancelService`：取消链路，WMS code/msg 原样透传，无单号只做本地取消。
  - `ReconcileService`：本地订单与事件流水对账，四类差异。
结论：通过。未发现关键环节被 mock 掉，注释质量满足学习用途。

### 3.2 是否直连 WMS 库
- 检索 `jim_wms`、`jdbc:mysql`：数据源 URL 仅 `jim_oms_order`、`jim_oms_product`；无任何 jim_wms_* 数据源。
- 代码中出现的 `wms_outbound_no` 是 OMS 本地列名（注释），不是跨库表名。
- WMS 地址走配置项 `oms.wms.base-url`（默认 http://127.0.0.1:8089），经 RestTemplate HTTP 调用。
结论：通过。业务代码未直连 WMS 库。

### 3.3 README 与代码配置一致性
| 项 | README | 代码/配置 | 结论 |
| --- | --- | --- | --- |
| eureka 端口 | 8762 | application.yml 8762 | 一致 |
| product 端口/库 | 8090 / jim_oms_product | yml 8090 / jim_oms_product | 一致 |
| order 端口/库 | 8091 / jim_oms_order | yml 8091 / jim_oms_order | 一致 |
| gateway 端口 | 8092 | yml 8092 | 一致 |
| WMS base-url | http://127.0.0.1:8089 | `oms.wms.base-url` 同值 | 一致 |
| product base-url | http://127.0.0.1:8090 | `oms.product.base-url` 同值 | 一致 |
| 超时 connect/read | WMS 3000/10000；product 3000/5000 | ClientConfig 与 yml 同值 | 一致 |
| 补偿扫描/重试 | 2000ms / 20 | yml `oms.push.scan-interval-ms=2000`、`max-retry=20` | 一致 |
| Kafka 消费组 | oms-order-service | yml group-id 同值 | 一致 |
| 启动顺序 | eureka→product→order→gateway | start-oms.ps1 数组同序 | 一致 |
| 网关路由 | /product→/api/products/**；/order→/api/orders/** | gateway yml RewritePath 同值 | 一致 |
结论：通过。未发现端口、库名、配置默认值、启动顺序与代码不符。

### 3.4 learning-guide 类方法真实性
抽查指南引用并在代码中核实：
- `OrderController.create`（OrderController.java:38）存在。
- `OrderService.createOrder`（OrderService.java:49）存在。
- `ProductQueryClient.getProduct`（ProductQueryClient.java:32）存在。
- `ProductController.get` → `ProductService.getByOmsSkuCode`（ProductController.java:35、ProductService.java:47）存在。
- `PushWorker.pushIfPending/sweep/pushOne/claim/buildCmd/handleSuccess` 均存在。
- `StatusEventConsumer.onOrderStatus`（:38）存在。
- `WmsIntegrationClient.createOutboundOrder/cancelOutboundOrder` 存在。
结论：通过。指南列出的类与方法名真实存在，非泛泛而谈。

### 3.5 报告「未完成/未验证项」如实性
- **Redis 未接入**：核实通过。order/product/gateway/common 全部 pom 无 redis 依赖，yml 无 Redis 连接项，代码无 RedisTemplate。与报告一致。
- **WMS 无查询接口未被绕过**：核实通过。`wms-integration-service` 源码 `@GetMapping` 0 命中；OMS 对账只依赖本地订单与本地事件表，未反查 WMS 库。与报告一致。
- **WMS 残留清单**：报告列 11 条旧残留；复核启动前只读 SQL 库内恰为这 11 条（状态、出库单号逐条一致），含 OMS-CANCEL-001 停留在 CREATED。与报告一致。
- **Kafka 控制台个别 offset 回显不全**：属实。本机 .bat 在非交互/Start-Process 下参数被拆分，需直调 java ConsoleConsumer；中文经控制台显示为 GBK 乱码，取证以 SQL 中 raw_value 为准。报告已如实标注。

## 4. 与 verification-report.md 逐条对照

| 报告结论 | 复核结果 | 说明 |
| --- | --- | --- |
| 全量构建通过、四服务启动注册 8762 | 证实 | 本次冷启动 UP 注册三实例；jar（14:52）晚于源码（14:35），运行的是最新构建 |
| 端到端建单生成 SALE_OUT、回填单号、两侧 FINISHED | 证实 | REV-1 链路实测，SALE_OUT/CREATED→FINISHED，Kafka p2/o8 直读一致 |
| 消费事件推进终态并落 partition/offset/key/raw | 证实 | p2/o8、key=OUT...372695、FINISHED/出库完成 |
| 重复提交 409，三侧各 1 张 | 证实 | REV-1 计数 1/1/1 |
| 未拣货取消两侧 CANCELLED | 证实 | REV-2，p2/o9 CANCELLED |
| 已拣货取消 409 原文透传、状态不变 CANCELLED | 证实 | REV-3，msg 逐字匹配，OMS 保持 PICKED |
| 对账定位 STATUS_MISMATCH，还原后消失 | 证实 | REV-1 篡改/还原两轮实测 |
| 崩溃后补偿自愈、仅一张单 | 证实 | REV-4，强杀 order、重启、恢复 8089 后 DONE/WAITING_WMS，全局仅 +1 出库单与登记 |
| 历史事件 offset（p1/o10 FINISHED、p1/o11 CANCELLED、p0/o13 PICKED） | 证实 | 直读 Kafka 对应 offset，key 与状态与报告一致 |
| Redis 未接入 | 证实 | pom/yml/代码均无 Redis |
| WMS 无查询接口、未绕过 | 证实 | integration 无 GET，对账仅用本地表 |
| WMS 残留 11 条不可删 | 证实 | 只读 SQL 逐条一致 |
| 多实例并发下发未压测 | 未验证（报告已声明） | 本次单实例复测，未做多实例 |
| 503 业务码 / FAILED 登记重置重试精确分支 | 未验证（报告已声明） | 本次测的是连接拒绝（503 语义）补偿自愈，未构造 WMS 返回 503 body 与 FAILED 登记重置样本 |

## 5. 复核中发现的需记录事项（非阻断）

1. **HTTP 状态码口径**：OMS 与 WMS 业务失败时 HTTP 状态仍为 200，成败只看 body.code。报告与代码均如此处理；调用方若按 HTTP 状态码判断会误判。这是契约事实，不是缺陷，但对接前端时需注意。
2. **PS 控制台中文乱码**：Invoke-RestMethod 直读响应体在 PowerShell 5.1 下按 GBK 解码显示为乱码；实际 body 是 UTF-8，用 RawContentStream 按 UTF-8 解码后原文正确。脚本与报告已注意到这一点，证据以 SQL raw_value 为准。
3. **崩溃任务 last_error 残留**：REV-4 补偿成功后，`oms_order.last_error` 被显式清空（NULL），但 `oms_push_task.last_error` 仍保留首次 Connection refused 文本（任务状态已 DONE）。属记录性残留，不影响状态与幂等；报告验收项 6 只写「last_error 在成功后被显式清空」，针对的是订单行，与代码一致，任务行未清空属可接受的细节。
4. **对账基线存在 EVENT_NO_ORDER**：入库完成事件 IN1794985268271 在 OMS 无对应订单，被列为 EVENT_NO_ORDER。这是夹具入库事件进入同一 topic 的正常归类，不是 OMS 漏单。
5. **Kafka 控制台 .bat 在本环境参数传递异常**：`--timeout-ms` 等参数经 Start-Process/.bat 链路被拆成命令，需直调 java。报告未展开命令细节，复跑者可能踩此坑。

## 6. 本次复核产生的数据与清理

### 6.1 OMS 自有库（已清理）
- 新建订单 REV-1、REV-2、REV-3、REV-4；明细 4 行；任务 4 行；事件 3 行；商品 REV-SKU-001。
- 清理后：`oms_order=0`、`oms_order_item=0`、`oms_push_task=0`、`oms_wms_event=0`；`oms_product=1`（保留原有样例 OMS-SKU-001）。

### 6.2 WMS 侧残留（受只读边界未删，列出清单）
```
REV-1  SALE_OUT FINISHED    OUT1794985372695
REV-2  SALE_OUT CANCELLED   OUT1794985675866
REV-3  SALE_OUT PICKED      OUT1794985711422
REV-4  SALE_OUT CREATED      OUT1794985923429
```
对应登记记录同单号各一条。连同报告原 11 条旧残留，WMS 侧本次复核后共 15 条出库单、15 条登记。

### 6.3 端口与进程收尾
- OMS 停止：stop-oms.ps1，PID 36580/17096/20440/29236；8762/8090/8091/8092 全部释放。
- WMS 夹具停止：stop-wms-fixture.ps1，PID 26560/20488/16808/33624/24492/28032/30948；8761/8081–8089 全部释放。
- 中间件：3306、6379、9092 仍 LISTEN；无 oms-/wms-backend 残留 java 进程。
- `D:\wms`：15:00 之后无新增/修改文件；14:07–14:58 的 .idea 与 target/classes 时间戳为既有 IDE/历史构建产物，非本轮复核写入。

## 7. 总体结论

- 任务书要求的可实测项（冷启动注册、端到端建单、事件回传、幂等、两类取消、对账、崩溃恢复、端口清理、边界只读）本次全部独立复测通过，未发现夸大或被推翻的自述结论。
- 报告的「未完成/未验证项」表述如实：Redis 未接入、无 WMS 查询接口、多实例未压测、503/FAILED 重置分支未单独构造，均与代码和实测一致。
- 最值得修正/补充之处：① 在复跑指引里写明 PowerShell 5.1 下响应体需按 UTF-8 解码、Kafka 控制台 .bat 参数异常时直调 java；② 明确任务行 last_error 在成功后不清空这一细节；③ 说明对账基线的 EVENT_NO_ORDER 来自夹具入库事件，避免误读为漏单。
