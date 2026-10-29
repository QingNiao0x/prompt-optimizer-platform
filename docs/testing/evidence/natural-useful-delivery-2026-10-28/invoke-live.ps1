[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage,
    [Parameter(Mandatory)][ValidatePattern('^[A-Z0-9_-]+$')][string] $Job
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
if ($Job -eq 'COUNT') { & node (Join-Path $PSScriptRoot 'run-live.mjs') $Stage COUNT; exit $LASTEXITCODE }
$evaluationRoot = Join-Path $repoRoot ('tmp/natural-useful-delivery-2026-10-28/' + $Stage)
$ownership = Get-Content -Raw -LiteralPath (Join-Path $evaluationRoot 'process.json') | ConvertFrom-Json
$owned = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$ownership.pid) -ErrorAction Stop
if (-not $owned -or -not $owned.CommandLine.Contains('NaturalUsefulDeliveryEvaluationServer') -or -not $owned.CommandLine.Contains($evaluationRoot)) { throw 'Evaluation process identity mismatch.' }
if ($Job -ne 'LOGIN') { & node (Join-Path $PSScriptRoot 'run-live.mjs') $Stage $Job; exit $LASTEXITCODE }
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path $repoRoot 'tmp/local-acceptance/credential.dpapi')).Trim())
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
    $env:POP_EVAL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
    & node (Join-Path $PSScriptRoot 'run-live.mjs') $Stage $Job
    $result = $LASTEXITCODE
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
    Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
    $secure.Dispose()
}
exit $result
