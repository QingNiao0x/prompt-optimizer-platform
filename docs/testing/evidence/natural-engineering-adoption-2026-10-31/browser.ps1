$ErrorActionPreference='Stop'
$secure=ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path $PSScriptRoot '../../../../tmp/local-acceptance/credential.dpapi')).Trim())
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
  $env:POP_EVAL_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
  & node (Join-Path $PSScriptRoot 'browser.mjs')
  $result=$LASTEXITCODE
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
  Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
  $secure.Dispose()
}
exit $result
