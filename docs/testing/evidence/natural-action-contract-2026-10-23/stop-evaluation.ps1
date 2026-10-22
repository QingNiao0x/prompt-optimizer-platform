#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$runtimeRoot = Join-Path $repoRoot ('tmp/natural-action-contract-2026-10-23/' + $Stage)
$record = Get-Content -LiteralPath (Join-Path $runtimeRoot 'process.json') -Raw | ConvertFrom-Json
if ($record.stage -ne $Stage -or $record.port -ne 9003 -or $record.workspace -ne $repoRoot) { throw '进程归属记录不匹配。' }
$process = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $record.pid) -ErrorAction Stop
if ($process) {
    # PID可能复用，必须同时核对本轮入口与精确运行副本路径，不触碰日常9000服务。
    if ($process.Name -ne 'java.exe' -or $process.CommandLine -notlike '*NaturalActionContractEvaluationServer*' -or
        -not $process.CommandLine.Contains('-Dnatural.eval.root=' + $runtimeRoot)) { throw 'PID已由其他进程占用，不停止。' }
    Stop-Process -Id $record.pid -ErrorAction Stop
}
$remaining = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9003)
[pscustomobject]@{stage=$Stage;ownedProcessStopped=$true;port9003Released=($remaining.Count -eq 0);dailyServicesChanged=$false} | ConvertTo-Json -Compress
