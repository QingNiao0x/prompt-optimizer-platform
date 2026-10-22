#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('all','count')]
    [Parameter(Mandatory)]
    [string] $Command
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
$canonicalRoot = 'E:\MyProject\prompt-optimizer-platform'
$credentialPath = Join-Path $canonicalRoot 'tmp/local-acceptance/credential.dpapi'
if (-not (Test-Path -LiteralPath $credentialPath)) { throw '缺少固定合成账号凭据，禁止创建替代账号。' }
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText($credentialPath).Trim())
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
    # 只在子进程环境中传递本机合成凭据，既不打印，也不写进参数、证据或历史。
    $env:POP_CANONICAL_ROOT = $canonicalRoot
    $env:POP_EVAL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
    & node (Join-Path $PSScriptRoot 'run-evaluation.mjs') $Command
    $result = $LASTEXITCODE
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
    Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:POP_CANONICAL_ROOT -ErrorAction SilentlyContinue
    $secure.Dispose()
}
exit $result
