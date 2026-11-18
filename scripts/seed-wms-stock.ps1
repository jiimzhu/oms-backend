# 联调夹具：向 WMS 铺 SKU-001 库存 20 件到 DC01-A-01-01 / B-DEMO。
# 走 WMS 公开入库 API，属于测试手段；OMS 业务代码不依赖这些接口。
$ErrorActionPreference = 'Stop'
$inbound = 'http://127.0.0.1:8083'
$report  = 'http://127.0.0.1:8088'
$sku = 'SKU-001'; $wh = 'DC01'; $loc = 'DC01-A-01-01'; $batch = 'B-DEMO'; $qty = 20
$sourceNo = 'DEMO-PO-OMS-' + (Get-Date -Format 'HHmmss')

function Post-Json([string]$url, $body) {
    $json = $body | ConvertTo-Json -Depth 8 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 30
}

Write-Output '=== 铺库存前 ==='
$before = Invoke-RestMethod -Uri ($report + '/api/report/stock-summary?warehouseCode=' + $wh) -TimeoutSec 20
Write-Output ($before | ConvertTo-Json -Compress)

$order = Post-Json ($inbound + '/api/inbound/order') @{
    inboundType = 'PURCHASE'; sourceSystem = 'DEMO'; sourceNo = $sourceNo
    warehouseCode = $wh; items = @(@{ skuCode = $sku; batchNo = $batch; expectQty = $qty })
}
$inboundNo = $order.data.inboundNo
Write-Output ('入库单号 ' + $inboundNo)

Post-Json ($inbound + '/api/inbound/receive') @{ inboundNo = $inboundNo; skuCode = $sku; qty = $qty } | Out-Null
Write-Output '收货 20 件'
Post-Json ($inbound + '/api/inbound/qc') @{ inboundNo = $inboundNo; skuCode = $sku; result = 'PASS' } | Out-Null
Write-Output '质检 PASS'
Post-Json ($inbound + '/api/inbound/putaway') @{ inboundNo = $inboundNo; skuCode = $sku; locationCode = $loc; qty = $qty } | Out-Null
Write-Output ('上架到 ' + $loc)

Start-Sleep -Seconds 2
Write-Output '=== 铺库存后 ==='
$after = Invoke-RestMethod -Uri ($report + '/api/report/stock-summary?warehouseCode=' + $wh) -TimeoutSec 20
Write-Output ($after | ConvertTo-Json -Compress)
