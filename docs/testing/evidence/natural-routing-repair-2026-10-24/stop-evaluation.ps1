#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$evaluationRoot = Join-Path $repoRoot ('tmp/natural-routing-repair-2026-10-24/' + $Stage)
$ownership = Get-Content -LiteralPath (Join-Path $evaluationRoot 'process.json') -Raw | ConvertFrom-Json
if ($ownership.stage -ne $Stage -or $ownership.workspace -ne $repoRoot -or $ownership.port -ne 9003 -or
    $ownership.process -ne 'NaturalRoutingRepairEvaluationServer') { throw '不属于本阶段的评测进程，拒绝停止。' }
# PID可能被复用；先核对可执行路径、主类、私有阶段目录与创建时间，再操作这个单独PID。
$owned = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$ownership.pid) -ErrorAction Stop
if (-not $owned) {
    [pscustomobject]@{status='ALREADY_STOPPED';pid=$ownership.pid;port=9003} | ConvertTo-Json -Compress
    exit 0
}
if ($owned.ExecutablePath -ne $ownership.executable -or
    -not $owned.CommandLine.Contains('NaturalRoutingRepairEvaluationServer') -or
    -not $owned.CommandLine.Contains($evaluationRoot) -or
    $owned.CreationDate.ToUniversalTime().Ticks -ne ([DateTimeOffset]$ownership.processStartedAt).UtcTicks) {
    throw '进程身份或创建时间变化，不停止其他会话进程。'
}
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9003)
if (@($listeners | Where-Object OwningProcess -ne $ownership.pid).Count) {
    throw '9003监听已归其他进程，不操作。'
}
Stop-Process -Id ([int]$ownership.pid) -ErrorAction Stop
[pscustomobject]@{status='STOPPED_OWNED_EVALUATION_ONLY';pid=$ownership.pid;port=9003;daily9000Touched=$false} | ConvertTo-Json -Compress
