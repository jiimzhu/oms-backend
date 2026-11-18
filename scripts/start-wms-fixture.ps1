# WMS 联调夹具启动脚本（只读使用 D:\wms 的现有 jar，不重新构建、不写 D:\wms）。
# 日志落到 D:\oms-new\logs\wms；只启动联调必需的 WMS 服务，端口与 WMS 注册中心 8761 隔离于 OMS 的 8762。
# 用法：在 D:\oms-new 目录执行  .\scripts\start-wms-fixture.ps1
$ErrorActionPreference = 'Stop'
$wmsRoot = 'D:\wms\wms-backend'
$logDir = 'D:\oms-new\logs\wms'
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

function Test-PortOpen([int]$port) {
    try { return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }
    catch { return $false }
}

function Wait-Port([int]$port, [string]$label, [int]$timeoutSec = 150) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        if (Test-PortOpen $port) {
            Write-Output ("  OK  " + $label + " 端口 " + $port + " 已就绪，用时 " + [int]$sw.Elapsed.TotalSeconds + " 秒")
            return $true
        }
        Start-Sleep -Milliseconds 1500
    }
    Write-Output ("  XX  " + $label + " 端口 " + $port + " 未就绪，请看 " + $logDir)
    return $false
}

function Get-ServiceJar([string]$module) {
    $targetDir = Join-Path $wmsRoot (Join-Path $module 'target')
    if (-not (Test-Path -LiteralPath $targetDir)) { return $null }
    $jar = Get-ChildItem -LiteralPath $targetDir -Filter '*.jar' -File |
        Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

Write-Output '--- 中间件检查 ---'
foreach ($p in @(3306, 6379, 9092)) {
    if (Test-PortOpen $p) { Write-Output ("  中间件端口 " + $p + " 在线") }
    else { Write-Output ("  警告：端口 " + $p + " 未监听"); exit 1 }
}

$order = @(
    @{ module = 'wms-eureka-server';      port = 8761 },
    @{ module = 'wms-admin-service';       port = 8081 },
    @{ module = 'wms-inventory-service';  port = 8082 },
    @{ module = 'wms-inbound-service';     port = 8083 },
    @{ module = 'wms-outbound-service';   port = 8084 },
    @{ module = 'wms-integration-service'; port = 8089 },
    @{ module = 'wms-report-service';      port = 8088 }
)

$pids = @()
foreach ($svc in $order) {
    if (Test-PortOpen $svc.port) {
        Write-Output ("  跳过 " + $svc.module + "：端口 " + $svc.port + " 已占用")
        continue
    }
    $jar = Get-ServiceJar $svc.module
    if (-not $jar) {
        Write-Output ("  跳过 " + $svc.module + "：未找到 jar")
        continue
    }
    $outLog = Join-Path $logDir ($svc.module + '.log')
    $errLog = Join-Path $logDir ($svc.module + '.err.log')
    $proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar) -WorkingDirectory $wmsRoot `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
    $pids += $proc.Id
    Write-Output ("  已启动 " + $svc.module + "，PID=" + $proc.Id)
    Wait-Port $svc.port $svc.module | Out-Null
}

$pids | Set-Content -LiteralPath (Join-Path $logDir 'wms-pids.txt') -Encoding utf8
Write-Output '--- WMS 夹具状态 ---'
foreach ($svc in $order) {
    $state = if (Test-PortOpen $svc.port) { 'UP  ' } else { 'DOWN' }
    Write-Output ("  " + $state + "  " + $svc.module + "  " + $svc.port)
}
