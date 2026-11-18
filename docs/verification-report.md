# OMS 联调验证报告

本报告记录 2026-11-18 在本机对 `D:\oms-new` 的真机验收。结论均来自实际命令输出；推断内容已标注。中间件 MySQL 3306、Redis 6379、Kafka 9092 全程保持运行，未重启、未停止。WMS 服务仅作为联调夹具由 `scripts\start-wms-fixture.ps1` 用既有 jar 启动，验收后已停止。

完整数据库快照见 `docs\evidence-e3.md`（OMS 订单、事件流水、任务、WMS 出库单与登记记录，只读查询）。

## 环境与版本

- JDK 17.0.21、Maven 3.9.9。
- Spring Boot 3.2.5、Spring Cloud 2023.0.2、MyBatis-Plus 3.5.5，版本取自 `D:\wms\wms-backend` 父工程。
- OMS 端口：eureka 8762、product 8090、order 8091、gateway 8092；库：`jim_oms_product`、`jim_oms_order`。
- WMS 夹具端口：8761、8081、8082、8083、8084、8088、8089。

## 验收项 1：全量构建与注册

### 命令

```powershell
cd D:\oms-new
mvn -B -DskipTests clean package
.\start-oms.ps1
# 注册列表
Invoke-RestMethod -Uri http://127.0.0.1:8762/eureka/apps -Headers @{ Accept='application/json' }
```

### 输出片段

构建（`logs\build-final.log`）：

```
oms-parent ................................. SUCCESS
oms-common ................................. SUCCESS
oms-eureka-server ......................... SUCCESS
oms-product-service ...................... SUCCESS
oms-order-service ........................ SUCCESS
oms-gateway ............................... SUCCESS
BUILD SUCCESS
```

Eureka 实例列表（实测）：

```
OMS-ORDER-SERVICE   instances=1
   -> oms-order-service:8091   status=UP   host=127.0.0.1:8091
OMS-PRODUCT-SERVICE instances=1
   -> oms-product-service:8090 status=UP  host=127.0.0.1:8090
OMS-GATEWAY         instances=1
   -> oms-gateway:8092         status=UP  host=127.0.0.1:8092
```

### 结论

全量构建通过；product、order、gateway 三个服务均注册到 OMS 自己的 8762 注册中心，实例状态 UP，地址固定 127.0.0.1。eureka 自身按配置不注册。网关路由经 `http://127.0.0.1:8092/order/...` 与 `/product/...` 实测返回 200。

## 验收项 2：端到端建单

### 命令

```powershell
# 下单（直连 order 8091）
Invoke-RestMethod -Uri http://127.0.0.1:8091/api/orders -Method Post `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($json))
# 只读核对 WMS 出库单与登记记录
mysql -uroot -proot -e "SELECT source_no,order_type,status,outbound_no FROM jim_wms_outbound.wms_outbound_order WHERE source_no='OMS-E3-001';"
```

### 输出片段

- OMS 创建返回 `code=200`，初始状态 `PENDING_PUSH`，随后变为 `WAITING_WMS`，回填 `wmsOutboundNo=OUT1794983899396`，金额 298.50（单价 99.50 × 3）。
- WMS 出库单（只读）：

```
source_no       order_type  status    outbound_no
OMS-E3-001      SALE_OUT    CREATED   OUT1794983899396
```

- 两侧计数：OMS 订单 1、WMS 出库单 1、WMS 登记记录 1。

拣货与确认由 WMS 公开夹具 API（8084）驱动，属于测试手段，不是 OMS 业务依赖。确认后：

```
OMS 状态 FINISHED，WMS 状态 FINISHED，出库单 OUT1794983899396
```

### 结论

下单成功后 WMS 真实生成 `SALE_OUT` 出库单，OMS 保存出库单号；拣货确认完成后两侧状态一致为 FINISHED。WMS 侧核对使用只读 SELECT 与公开 API，业务代码未直连 WMS 库。

## 验收项 3：状态事件回传

### 命令

```powershell
# 读取本地事件流水（OMS 自有库）
mysql -uroot -proot -e "SELECT kafka_partition,event_offset,message_key,status,message,raw_value FROM jim_oms_order.oms_wms_event WHERE wms_outbound_no='OUT1794983899396';"
# Kafka 命令行查看各分区最新 offset
& "$K\kafka-get-offsets.bat" --bootstrap-server 127.0.0.1:9092 --topic wms-order-status
```

### 输出片段

本地事件流水（FINISHED）：

```
kafka_partition=1  event_offset=10  message_key=OUT1794983899396  status=FINISHED
message=出库完成
raw_value={"orderNo":"OUT1794983899396","message":"出库完成","status":"FINISHED"}
consume_time=2026-11-18 14:41:26
```

Kafka 分区最新 offset（实测）：

```
wms-order-status:0:14
wms-order-status:1:12
wms-order-status:2:8
```

同批其他事件（证据快照）：

```
partition=1 offset=11 key=OUT1794984118684 status=CANCELLED  raw={"...","message":"CTM 取消单据: OMS-E3-C1","status":"CANCELLED"}
partition=0 offset=13 key=OUT1794984144096 status=PICKED     raw={"...","message":"部分出库","status":"PICKED"}
```

### 结论

OMS 消费 `wms-order-status` 后把原始消息（partition、offset、key、value）落库取证，并按 WMS 出库单号反查订单推进到 FINISHED。事件 offset 与消息原文在本地事件表可查。控制台对中文存在 PS 解码显示偏差，数据库实际存储为 UTF-8，以直读 SQL 结果为准。

## 验收项 4：重复提交幂等

### 命令

对同一业务单号 `OMS-E3-001` 再次发起下单，随后三侧计数。

### 输出片段

```
重复提交返回 code=409  msg=业务单号重复，请勿重复提交: OMS-E3-001
oms=1  wms_out=1  wms_reg=1
```

### 结论

同一业务单号重复提交被明确拒绝；OMS 库只有一张订单，WMS 出库单与登记记录各只有一张。

## 验收项 5：取消链路

### 5A 未拣货取消（成功）

订单 `OMS-E3-C1`，状态 `WAITING_WMS`，出库单 `OUT1794984118684`，未拣货即取消。

```
cancel code=200
after: status=CANCELLED
event partition=1 offset=11 status=CANCELLED raw={"orderNo":"OUT1794984118684","message":"CTM 取消单据: OMS-E3-C1","status":"CANCELLED"}
WMS 出库单 status=CANCELLED
```

结论：未发货前取消同步到 WMS，OMS 与 WMS 均置 CANCELLED，并收到 CANCELLED 事件。

### 5B 已拣货（部分出库）取消（被拒，原样回传）

订单 `OMS-E3-P1`，计划 2、拣 1、确认后 WMS 与 OMS 均为 `PICKED`。发起取消：

```
cancel code=409
msg=已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存
oms after reject: PICKED
```

### 结论

WMS 拒绝原因与原文 message 原样呈现给调用方，未吞掉、未归一成统一错误；OMS 订单保持 PICKED，未被错误改成 CANCELLED。

边界说明：WMS 源码只拦截 FINISHED 与 PICKED 且存在拣货明细两种情形；状态仍为 PICKING、已有拣货量但尚未扣减库存时不拦截。本报告 5A 之外的 PICKING 阶段取消属于该源码边界，不代表业务建议。

## 验收项 6：崩溃恢复

两个真实场景均在不使用睡眠开关的前提下完成。

### 场景 A：进程在待下发窗口被强杀

1. 先停 WMS integration（8089），使建单连接被拒绝。
2. 创建订单 `OMS-E3-CRASH`。杀进程前快照：

```
order_no      status  wms_outbound_no  last_error
OMS-E3-CRASH  FAILED   NULL             503: 调用 WMS 建单网络异常: ResourceAccessException: Connection refused
task: FAILED retry_count=1
wms_reg=0
```

3. 强杀 order 服务进程（端口 8091 释放）。杀后数据库快照：

```
OMS-E3-CRASH  FAILED  NULL
task: FAILED retry_count=1
wms_reg=0
```

4. 重启 order 服务（integration 仍停着）。重启后：

```
OMS-E3-CRASH  FAILED  NULL
task: FAILED retry_count=2
wms_reg=0  wms_out=0
```

结论：进程被杀后本地订单与任务仍在，重启后没有产生第二张单据，也没有静默丢失。

### 场景 B：WMS 恢复后补偿自愈

重启 integration（8089）后等待补偿 worker 扫描重试：

```
oms status=WAITING_WMS  wms=OUT1794984277775
order: WAITING_WMS  last_error=NULL
task: DONE retry_count=2
wms_reg=1
WMS 出库单: OMS-E3-CRASH SALE_OUT CREATED OUT1794984277775
```

### 结论

WMS 恢复后补偿任务自动下发成功，只生成一张 WMS 出库单，OMS 回填单号进入 WAITING_WMS；无重复单、无第二张 WMS 单。口径说明：补偿成功后**订单行** `oms_order.last_error` 会被显式清空（NULL）；**任务行** `oms_push_task.last_error` 仍保留首次失败文本（如 Connection refused），这是记录性残留，任务状态已置 DONE、订单状态已恢复，不属于状态未恢复。该任务行错误文本是否需要在成功时一并清空，列为待改进项，本轮未改代码。

## 验收项 7：对账

### 基线

`GET /api/orders/reconcile` 基线只有一条合理差异：

```
ORDER_NO_EVENT | OMS-E3-CRASH | oms=WAITING_WMS | 订单未收到任何 WMS 状态事件
```

### 人为制造不一致

把已 FINISHED 的 `OMS-E3-001` 本地状态改为 `WAITING_WMS`，再跑对账：

```
STATUS_MISMATCH | OMS-E3-001 | OUT1794983899396 | oms=WAITING_WMS | wms=FINISHED
ORDER_NO_EVENT  | OMS-E3-CRASH | OUT1794984277775 | oms=WAITING_WMS
```

### 恢复后复核

把状态改回 FINISHED 后，STATUS_MISMATCH 消失，只剩 CRASH 单的 ORDER_NO_EVENT。

### 结论

对账能精确到具体业务单号与差异类型（STATUS_MISMATCH），差异清单可执行复跑；人为改动已还原。差异类型还覆盖 CONFLICT_NO_OUTBOUND_NO、EVENT_NO_ORDER、ORDER_NO_EVENT，定义见 `ReconcileService.java`。

## 验收项 8：清理与端口释放

### 命令

```powershell
.\stop-oms.ps1
.\scripts\stop-wms-fixture.ps1
# 中间件在线确认
foreach ($p in 3306,6379,9092) { Get-NetTCPConnection -LocalPort $p -State Listen }
# 清理 OMS 自有库交易测试行
mysql -uroot -proot -e "DELETE FROM jim_oms_order.oms_wms_event; DELETE FROM jim_oms_order.oms_push_task; DELETE FROM jim_oms_order.oms_order_item; DELETE FROM jim_oms_order.oms_order;"
```

### 输出片段

OMS 端口释放确认：

```
已释放: 8762
已释放: 8090
已释放: 8091
已释放: 8092
```

WMS 夹具端口释放确认：

```
已释放: 8761
已释放: 8081  8082  8083  8084  8085  8086  8087  8088  8089
```

中间件：

```
middleware 3306 listening: True
middleware 6379 listening: True
middleware 9092 listening: True
```

OMS 自有库清理后：`oms_order=0`；商品主数据 `oms_product=1`（教学样例商品，非交易单据，保留）。

### WMS 侧残留说明

边界禁止向 WMS 库写数据，因此联调产生的 WMS 出库单与登记记录无法在 OMS 侧删除。残留清单（只读，来自证据快照）：

```
OMS-EO-001      FINISHED  OUT1794982983221
OMS-CANCEL-001  CREATED   OUT1794983048210
OMS-EO2-001     FINISHED  OUT1794983240587
OMS-CANCEL-002  CANCELLED OUT1794983303777
OMS-PICKED-001  CANCELLED OUT1794983337985
OMS-PARTIAL-001 PICKED    OUT1794983367680
OMS-CRASH-001   CREATED   OUT1794983572362
OMS-E3-001      FINISHED  OUT1794983899396
OMS-E3-C1       CANCELLED OUT1794984118684
OMS-E3-P1       PICKED    OUT1794984144096
OMS-E3-CRASH    CREATED   OUT1794984277775
```

### D:\wms 写入检查

本工程未在 `D:\wms` 执行任何 maven 构建，未创建 `D:\wms\logs`。目录内现存的 `.r2-evidence`、`docs\VERIFICATION-REPORT.md`、`.idea`、个别 `target\classes` 文件时间戳为 13:30–14:17，属于此前 WMS 核验轮次或 IDE 产生，不属于本次 OMS 联调写入；按只读边界未删除这些既有文件。

## 独立复核

在本报告自述结论之外，另由独立 Agent 从冷启动重跑整套链路做了一轮交叉复核：

- 复核方式：用交付脚本冷启动 OMS 与 WMS 夹具，使用本次新建单号 REV-1 ~ REV-4 独立取证，只读访问 WMS 库，未写入、未重新构建 `D:\wms`。
- 复核范围与结果：冷启动注册、端到端建单、事件回传、幂等、5A 未拣货取消、5B 已拣货取消被拒、对账、崩溃恢复均复测通过；历史事件 offset 逐条核对一致（p1/o10 FINISHED、p1/o11 CANCELLED、p0/o13 PICKED）。逐条命令、输出与计数见 `docs\review-independent-report.md`。
- 复核结论：本报告核心自述结论未被推翻，未发现被夸大的已实现项。
- 仍未验证的事项（与本报告「未完成与未验证项」一致，复核轮次同样未覆盖）：
  1. 多实例并发下发压测；本轮为单实例复测，未做多实例部署。
  2. WMS 返回 503 body 与 FAILED 登记重置重试的精确分支；本轮只测到连接拒绝（503 语义）后的补偿自愈，未单独构造该样本。

## 未完成与未验证项

1. **Redis 未接入**：当前链路无缓存需求，模块未引入 Redis 依赖，也未配置 Redis 连接。需求清单中的 Redis 能力未实现，不在本次结论范围内。
2. **按 OMS 单号反查 WMS 单据**：WMS integration 无查询接口，OMS 不具备该能力；冲突单只能人工核对，这是契约缺口，未绕过。
3. **WMS 测试残留单据**：受只读边界限制未清理，清单见上。
4. **Kafka 控制台消费者读取个别 offset 消息**：本机 bat 控制台输出在非交互环境下未完整回显消息体；事件证据以本地 `oms_wms_event` 表的 partition/offset/raw_value 快照为准（验收项 3 已满足）。
5. **高并发多实例下发**：当前补偿认领在单实例租约基础上实现，未做多实例部署压测；认领条件 UPDATE 已具备防并发重复下发能力，但多实例场景未实测。
6. **503 与 FAILED 重试分支**：代码区分 400/409/503，失败可重试；本次实测覆盖了网络拒绝（503 语义）后的补偿自愈，WMS 返回 503 业务码与 FAILED 登记重置重试的精确分支未单独构造样本。
7. **任务行 last_error 残留（待改进）**：补偿成功后订单行错误已清空，但 `oms_push_task.last_error` 保留首次失败文本。当前判定为记录性残留、不影响状态与幂等；是否在任务置 DONE 时一并清空该字段，留待后续评估，本轮未改动代码。

## 复跑指引

1. 启动中间件（已在运行则跳过）。
2. `.\scripts\start-wms-fixture.ps1` 启动 WMS 夹具，`.\scripts\seed-wms-stock.ps1` 铺库存。
3. `.\start-oms.ps1` 启动 OMS。
4. `.\scripts\acceptance-smoke.ps1` 跑自动化冒烟（注册、商品、下单、幂等、对账）。
5. 拣货、确认、部分出库取消、崩溃恢复等需要夹具驱动或强杀进程的步骤，命令与结果对照本报告验收项 2、5、6。
6. **控制台中文乱码坑**：PowerShell 5.1 控制台按 GBK 解码显示响应体，中文与部分 UTF-8 内容会显示成乱码。判断内容以直接读库（SQL 直读 `raw_value`）或按 UTF-8 解码后的结果为准，不要以控制台屏幕显示为准。
7. **Kafka 启动器参数坑**：本机 Kafka 的 `.bat` 启动器在非交互环境下参数会被拆断（例如 `--timeout-ms`）。复跑读 offset 时请直调 java 的 `ConsoleConsumer` 类，或在 `cmd /c` 单行命令里调用，并把输出重定向到文件后再按 UTF-8 读回，不要只看控制台回显。
