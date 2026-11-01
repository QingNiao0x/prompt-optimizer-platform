param()
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$secure=ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path $repo 'tmp/local-acceptance/credential.dpapi')).Trim())
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
  $env:POP_EVAL_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
  & node (Join-Path $PSScriptRoot 'browser-date-v2.mjs')
  exit $LASTEXITCODE
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
  Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
  $secure.Dispose()
}
