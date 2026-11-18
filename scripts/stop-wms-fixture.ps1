# 停止本次联调启动的 WMS 进程（命令行含 wms-backend），不碰 MySQL/Redis/Kafka。
# 停止后确认 8761 与 8081-8089 释放。
$ErrorActionPreference = 'Continue'
$ports = @(8761, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8089)

$matched = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*wms-backend*' }
foreach ($m in $matched) {
    Stop-Process -Id $m.ProcessId -Force
    Write-Output ("  已停止 WMS PID " + $m.ProcessId)
}

Start-Sleep -Seconds 2
Write-Output '--- 端口释放确认 ---'
foreach ($p in $ports) {
    $still = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($still) { Write-Output ("  仍被占用: " + $p) }
    else { Write-Output ("  已释放: " + $p) }
}
