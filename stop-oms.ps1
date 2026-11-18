# OMS 停止脚本：只停本工程启动的 java 进程（按 oms-pids.txt，再按命令行含 oms- 模块名兜底），
# 不触碰 WMS 与中间件。停止后逐个端口确认已释放。
$ErrorActionPreference = 'Continue'
$root = 'D:\oms-new'
$logDir = Join-Path $root 'logs\oms'
$ports = @(8762, 8090, 8091, 8092)

$stopped = @()
$pidsFile = Join-Path $logDir 'oms-pids.txt'
if (Test-Path -LiteralPath $pidsFile) {
    foreach ($line in Get-Content -LiteralPath $pidsFile) {
        $pidVal = 0
        if ([int]::TryParse($line.Trim(), [ref]$pidVal) -and $pidVal -gt 0) {
            $proc = Get-Process -Id $pidVal -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id $pidVal -Force
                $stopped += $pidVal
                Write-Output ("  已停止 PID " + $pidVal)
            }
        }
    }
}

# 兜底：按命令行匹配本工程模块，避免 PID 文件丢失时漏停
$matched = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*oms-eureka-server*' -or $_.CommandLine -like '*oms-product-service*' `
        -or $_.CommandLine -like '*oms-order-service*' -or $_.CommandLine -like '*oms-gateway*' }
foreach ($m in $matched) {
    if ($stopped -notcontains $m.ProcessId) {
        Stop-Process -Id $m.ProcessId -Force
        $stopped += $m.ProcessId
        Write-Output ("  兜底停止 PID " + $m.ProcessId)
    }
}

Start-Sleep -Seconds 2
Write-Output '--- 端口释放确认 ---'
foreach ($p in $ports) {
    $still = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($still) { Write-Output ("  仍被占用: " + $p) }
    else { Write-Output ("  已释放: " + $p) }
}
