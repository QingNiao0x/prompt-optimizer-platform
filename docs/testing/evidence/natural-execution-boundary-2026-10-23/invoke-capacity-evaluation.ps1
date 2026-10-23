#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage,
    [Parameter(Mandatory)][ValidateSet('all','generate','confirm','execute','review','author','count')][string] $Command
)
$ErrorActionPreference = 'Stop'
if ($Stage -ne 'capacity') { throw '容量入口仅接受capacity阶段。' }
if ($Command -notin @('execute','review','count')) { throw '容量入口禁止增强、Plan、全量和作者调用。' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
if ($Command -eq 'count') {
    $env:POP_CANONICAL_ROOT = $repoRoot
    try { & node (Join-Path $PSScriptRoot 'run-evaluation.mjs') $Command $Stage; $result = $LASTEXITCODE }
    finally { Remove-Item Env:POP_CANONICAL_ROOT -ErrorAction SilentlyContinue }
    exit $result
}
$evidence=Join-Path $PSScriptRoot $Stage
$evaluationRoot=Join-Path $repoRoot ('tmp/natural-execution-boundary-2026-10-23/' + $Stage)
foreach ($directory in @($PSScriptRoot,$evidence,(Split-Path -Parent $evaluationRoot),$evaluationRoot)) {
    if (Test-Path -LiteralPath (Join-Path $directory 'closure.json')) { throw '任务或阶段已闭批，不得执行。' }
}
& node (Join-Path $PSScriptRoot 'validate-capacity-stage.mjs') $Stage
if ($LASTEXITCODE -ne 0) { throw '冻结协议离线校验失败。' }
$ownership=Get-Content -LiteralPath (Join-Path $evaluationRoot 'process.json') -Raw | ConvertFrom-Json
if ($ownership.stage -ne $Stage -or $ownership.workspace -ne $repoRoot -or
    $ownership.port -ne 9003 -or $ownership.process -ne 'NaturalExecutionBoundaryEvaluationServer') {
    throw '9003进程记录不属于本阶段。'
}
# PID会复用；同时核对可执行文件和启动时刻，不能仅凭端口号接管另一会话。
$ownedProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$ownership.pid) -ErrorAction Stop
if (-not $ownedProcess -or $ownedProcess.ExecutablePath -ne $ownership.executable -or
    -not $ownedProcess.CommandLine.Contains('NaturalExecutionBoundaryEvaluationServer') -or
    -not $ownedProcess.CommandLine.Contains($evaluationRoot) -or
    $ownedProcess.CreationDate.ToUniversalTime().Ticks -ne ([DateTimeOffset]$ownership.processStartedAt).UtcTicks) {
    throw '9003进程已变化，拒绝发送评测。'
}
$listeners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9003)
if (-not $listeners.Count -or @($listeners | Where-Object OwningProcess -ne $ownership.pid).Count) {
    throw '9003当前监听不属于本阶段进程。'
}
$credentialPath = Join-Path $repoRoot 'tmp/local-acceptance/credential.dpapi'
if (-not (Test-Path -LiteralPath $credentialPath)) { throw '缺少固定合成账号凭据，禁止创建替代账号。' }
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText($credentialPath).Trim())
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
    # 凭据仅注入子进程环境，不进入命令行、日志或证据。
    $env:POP_CANONICAL_ROOT = $repoRoot
    $env:POP_EVAL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
    & node (Join-Path $PSScriptRoot 'run-evaluation.mjs') $Command $Stage
    $result = $LASTEXITCODE
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
    Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:POP_CANONICAL_ROOT -ErrorAction SilentlyContinue
    $secure.Dispose()
}
exit $result
