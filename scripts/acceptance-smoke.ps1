# OMS 自动化冒烟验收脚本（可复跑）。
# 前置：OMS 四个服务已由 start-oms.ps1 启动，WMS 夹具已由 scripts\start-wms-fixture.ps1 启动，
# 中间件 MySQL/Redis/Kafka 在运行。本脚本只调用 OMS 与 WMS 公开 HTTP 接口，不直连 WMS 库写数据。
# 用法：在 D:\oms-new 目录执行  .\scripts\acceptance-smoke.ps1
$ErrorActionPreference = 'Continue'

$omsOrder   = 'http://127.0.0.1:8091'
$omsProduct = 'http://127.0.0.1:8090'
$eureka     = 'http://127.0.0.1:8762'
$mysql      = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe'
$stamp      = Get-Date -Format 'HHmmss'
$orderNo    = 'OMS-ACC-' + $stamp

function Invoke-Json([string]$method, [string]$url, $bodyObj) {
    $json = if ($null -ne $bodyObj) { $bodyObj | ConvertTo-Json -Depth 8 -Compress } else { $null }
    $bytes = if ($null -ne $json) { [Text.Encoding]::UTF8.GetBytes($json) } else { $null }
    return Invoke-RestMethod -Uri $url -Method $method -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 20
}

function Assert-Port([int]$port, [string]$label) {
    $open = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($open) { Write-Output ("[PASS] {0} 端口 {1} 在线" -f $label, $port); return $true }
    Write-Output ("[FAIL] {0} 端口 {1} 未监听" -f $label, $port); return $false
}

# 1. 端口与注册中心实例列表
Write-Output '`n=== 1. 服务端口与 Eureka 注册 ==='
Assert-Port 8762 'eureka'; Assert-Port 8090 'product'; Assert-Port 8091 'order'; Assert-Port 8092 'gateway'
$apps = Invoke-RestMethod -Uri ($eureka + '/eureka/apps') -Headers @{ Accept = 'application/json' } -TimeoutSec 10
$registered = @()
foreach ($app in $apps.applications.application) {
    foreach ($inst in $app.instance) {
        if ($inst.status -eq 'UP') { $registered += $app.name }
        Write-Output ("  实例 {0}  id={1}  status={2}" -f $app.name, $inst.instanceId, $inst.status)
    }
}
foreach ($need in @('OMS-PRODUCT-SERVICE', 'OMS-ORDER-SERVICE', 'OMS-GATEWAY')) {
    if ($registered -contains $need) { Write-Output ("[PASS] 已注册: {0}" -f $need) }
    else { Write-Output ("[FAIL] 未注册: {0}" -f $need) }
}

# 2. 商品维护与查询
Write-Output '`n=== 2. 商品主数据 ==='
Invoke-Json 'Post' ($omsProduct + '/api/products/upsert') @{
    omsSkuCode = 'OMS-SKU-001'; name = '测试商品A'; price = 99.50
    wmsSkuCode = 'SKU-001'; warehouseCode = 'DC01'; locationCode = 'DC01-A-01-01'
    batchNo = 'B-DEMO'; enabled = 1
} | Out-Null
$p = Invoke-Json 'Get' ($omsProduct + '/api/products/OMS-SKU-001') $null
if ($p.code -eq 200 -and $p.data.wmsSkuCode -eq 'SKU-001') { Write-Output '[PASS] 商品查询返回映射信息' }
else { Write-Output '[FAIL] 商品查询异常' }

# 3. 下单主链路：等待回填 WMS 出库单号
Write-Output '`n=== 3. 下单主链路 ==='
$create = Invoke-Json 'Post' ($omsOrder + '/api/orders') @{
    orderNo = $orderNo; buyer = '验收脚本'; remark = 'acceptance-smoke'
    items = @(@{ omsSkuCode = 'OMS-SKU-001'; qty = 1 })
}
Write-Output ("  创建返回 code={0} status={1}" -f $create.code, $create.data.status)
$wmsNo = $null
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 2
    $detail = Invoke-Json 'Get' ($omsOrder + '/api/orders/' + $orderNo) $null
    if ($detail.data.wmsOutboundNo) { $wmsNo = $detail.data.wmsOutboundNo; break }
}
if ($wmsNo) { Write-Output ("[PASS] 订单已下发 WMS，出库单号={0} 状态={1}" -f $wmsNo, $detail.data.status) }
else { Write-Output '[FAIL] 未在等待窗口内回填 WMS 出库单号' }

# 4. 重复提交必须被拒绝，且两侧各只有一单
Write-Output '`n=== 4. 幂等重复提交 ==='
$dup = Invoke-Json 'Post' ($omsOrder + '/api/orders') @{
    orderNo = $orderNo; buyer = '验收脚本'
    items = @(@{ omsSkuCode = 'OMS-SKU-001'; qty = 1 })
}
Write-Output ("  重复提交 code={0} msg={1}" -f $dup.code, $dup.msg)
if ($dup.code -eq 409) { Write-Output '[PASS] 重复提交返回 409' } else { Write-Output '[FAIL] 重复提交未被拒绝' }
$counts = & $mysql -uroot -proot -e ("SELECT (SELECT COUNT(*) FROM jim_oms_order.oms_order WHERE order_no='" + $orderNo + "') AS oms, (SELECT COUNT(*) FROM jim_wms_outbound.wms_outbound_order WHERE source_no='" + $orderNo + "') AS wms_out, (SELECT COUNT(*) FROM jim_wms_integration.wms_external_order WHERE external_order_no='" + $orderNo + "') AS wms_reg;") 2>$null
Write-Output ($counts | Out-String)

# 5. 对账接口可执行并输出差异清单
Write-Output '`n=== 5. 对账 ==='
$recon = Invoke-Json 'Get' ($omsOrder + '/api/orders/reconcile') $null
if ($recon.code -eq 200) {
    Write-Output ('[PASS] 对账返回 {0} 条差异' -f $recon.data.Count)
    foreach ($d in $recon.data) { Write-Output ("  {0} | {1} | oms={2} | wms={3}" -f $d.diffType, $d.orderNo, $d.omsStatus, $d.latestWmsStatus) }
}
else { Write-Output '[FAIL] 对账接口异常' }

Write-Output ('`n订单号 {0}，WMS 出库单号 {1}' -f $orderNo, $wmsNo)
Write-Output '后续拣货/确认/取消等夹具步骤见 docs\verification-report.md 对应章节。'
