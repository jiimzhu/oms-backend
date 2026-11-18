# OMS 启动脚本：按 eureka -> product -> order -> gateway 顺序启动，逐个等端口就绪。
# 日志写入 D:\oms-new\logs\oms；PID 记录到 oms-pids.txt 供 stop-oms.ps1 精确停止。
# 用法：在 D:\oms-new 目录执行  .\start-oms.ps1
$ErrorActionPreference = 'Stop'
$root = 'D:\oms-new'
$logDir = Join-Path $root 'logs\oms'
if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

function Test-PortOpen([int]$port) {
    try {
        $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        return [bool]$c
    } catch { return $false }
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
    Write-Output ("  XX  " + $label + " 端口 " + $port + " 在 " + $timeoutSec + " 秒内未监听，请看日志")
    return $false
}

function Get-ServiceJar([string]$module) {
    $targetDir = Join-Path $root (Join-Path $module 'target')
    if (-not (Test-Path -LiteralPath $targetDir)) { return $null }
    $jar = Get-ChildItem -LiteralPath $targetDir -Filter '*.jar' -File |
        Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

Write-Output '--- 启动前中间件检查 ---'
foreach ($p in @(3306, 6379, 9092)) {
    if (Test-PortOpen $p) { Write-Output ("  中间件端口 " + $p + " 在线") }
    else { Write-Output ("  警告：端口 " + $p + " 未监听，相关功能会失败"); exit 1 }
}

$order = @(
    @{ module = 'oms-eureka-server';    port = 8762 },
    @{ module = 'oms-product-service';  port = 8090 },
    @{ module = 'oms-order-service';    port = 8091 },
    @{ module = 'oms-gateway';          port = 8092 }
)

$pids = @()
foreach ($svc in $order) {
    if (Test-PortOpen $svc.port) {
        Write-Output ("  跳过 " + $svc.module + "：端口 " + $svc.port + " 已被占用")
        continue
    }
    $jar = Get-ServiceJar $svc.module
    if (-not $jar) {
        Write-Output ("  跳过 " + $svc.module + "：未找到可执行 jar，请先执行 mvn -B -DskipTests clean package")
        continue
    }
    $outLog = Join-Path $logDir ($svc.module + '.log')
    $errLog = Join-Path $logDir ($svc.module + '.err.log')
    $proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar) -WorkingDirectory $root `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
    $pids += $proc.Id
    Write-Output ("  已启动 " + $svc.module + "，PID=" + $proc.Id + "，jar=" + (Split-Path -Leaf $jar))
    Wait-Port $svc.port $svc.module | Out-Null
}

$pids | Set-Content -LiteralPath (Join-Path $logDir 'oms-pids.txt') -Encoding utf8

Write-Output '--- 当前状态 ---'
foreach ($svc in $order) {
    $state = if (Test-PortOpen $svc.port) { 'UP  ' } else { 'DOWN' }
    Write-Output ("  " + $state + "  " + $svc.module + "  " + $svc.port)
}
