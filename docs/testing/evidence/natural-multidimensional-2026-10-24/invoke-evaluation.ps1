[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage,
    [Parameter(Mandatory)][ValidateSet('all','generate','confirm','execute','review','count')][string] $Command
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
$evaluationRoot = Join-Path $repoRoot ('tmp/natural-multidimensional-2026-10-24/' + $Stage)
if ($Command -eq 'count') {
    $env:POP_CANONICAL_ROOT = $repoRoot
    try { & node (Join-Path $PSScriptRoot 'run-evaluation.mjs') $Command $Stage; $result = $LASTEXITCODE }
    finally { Remove-Item Env:POP_CANONICAL_ROOT -ErrorAction SilentlyContinue }
    exit $result
}
$ownership = Get-Content -LiteralPath (Join-Path $evaluationRoot 'process.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$ownedProcess = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$ownership.pid) -ErrorAction Stop
if (-not $ownedProcess -or -not $ownedProcess.CommandLine.Contains('NaturalMultidimensionalEvaluationServer') -or
    -not $ownedProcess.CommandLine.Contains($evaluationRoot)) { throw 'Port 9003 process does not belong to this stage.' }
$credentialPath = Join-Path $repoRoot 'tmp/local-acceptance/credential.dpapi'
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText($credentialPath).Trim())
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
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
