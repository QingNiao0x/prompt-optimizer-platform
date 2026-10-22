#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage,
    [Parameter(Mandatory)][ValidateSet('all','generate','confirm','execute','review','author','count')][string] $Command
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
if ($Command -eq 'count') {
    $env:POP_CANONICAL_ROOT = $repoRoot
    try { & node (Join-Path $PSScriptRoot 'run-evaluation.mjs') $Command $Stage; $result = $LASTEXITCODE }
    finally { Remove-Item Env:POP_CANONICAL_ROOT -ErrorAction SilentlyContinue }
    exit $result
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
