[CmdletBinding()]
param(
    [ValidateSet('all','count','review')]
    [string] $Command,
    [ValidatePattern('^[A-Z0-9_]*$')]
    [string] $CaseId = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
$credentialPath = Join-Path $repoRoot 'tmp/local-acceptance/credential.dpapi'
if (-not (Test-Path -LiteralPath $credentialPath)) { throw '缺少固定合成账号凭据，禁止创建替代账号。' }
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText($credentialPath).Trim())
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
    # 只在子进程环境中传递本机合成凭据，既不打印，也不写进参数、证据或历史。
    $env:POP_EVAL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
    & node (Join-Path $PSScriptRoot 'run-correction.mjs') $Command $CaseId
    $result = $LASTEXITCODE
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
    Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
    $secure.Dispose()
}
exit $result
