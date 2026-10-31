param([ValidateSet('relay','api','browser')][string]$Mode)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$runtime=Join-Path $repo 'tmp/natural-feedback-delivery-2026-11-01'
[IO.Directory]::CreateDirectory($runtime) | Out-Null
if($Mode -eq 'relay'){
  $env:MODEL_API_KEY=[Environment]::GetEnvironmentVariable('MODEL_API_KEY','User')
  try { & node (Join-Path $PSScriptRoot 'product-relay.mjs'); exit $LASTEXITCODE }
  finally { Remove-Item Env:MODEL_API_KEY -ErrorAction SilentlyContinue }
}
if($Mode -eq 'api'){
  # 本机临时服务复用既有数据库与合成账号；不运行迁移，不接管9000日常服务。
  foreach($name in @('API_KEY_ENCRYPTION_SECRET','DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_URL')) {
    $value=[Environment]::GetEnvironmentVariable($name,'User')
    if($value){[Environment]::SetEnvironmentVariable($name,$value,'Process')}
  }
  $env:MODEL_API_KEY='fixture'
  $env:MODEL_DEEPSEEK_API_KEY='fixture'
  $env:MODEL_DEFAULT_PROVIDER='deepseek'
  $env:MODEL_DEEPSEEK_ENDPOINT='http://127.0.0.1:9078/chat/completions'
  $env:MODEL_MAX_TOKENS='8192'
  $env:MODEL_READ_TIMEOUT='180s'
  $cp=[IO.File]::ReadAllText((Join-Path $runtime 'classpath.txt')).Trim()
  $args=@('-Dfile.encoding=UTF-8', ('"-Djdk.net.unixdomain.tmpdir='+$runtime+'"'), '-cp', ('"'+$cp+'"'),
    'com.promptoptimizer.PromptOptimizerApplication','--server.port=9048','--server.address=127.0.0.1',
    '--spring.flyway.enabled=false','--spring.sql.init.mode=never','--app.enhancement.natural.profile=COMPLETION_BASIS_REPAIR')
  $process=Start-Process -FilePath 'E:/Delelop/Java/jdk-21/bin/java.exe' -ArgumentList $args -WorkingDirectory (Join-Path $repo 'services/api') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'api.log') -RedirectStandardError (Join-Path $runtime 'api.err')
  [IO.File]::WriteAllText((Join-Path $runtime 'api.pid'),[string]$process.Id)
  [pscustomobject]@{pid=$process.Id;port=9048;migrations=$false;modelEndpoint='localhost-counted-relay'}|ConvertTo-Json -Compress
  exit
}
$secure=ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path $repo 'tmp/local-acceptance/credential.dpapi')).Trim())
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
  $env:POP_EVAL_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
  & node (Join-Path $PSScriptRoot 'product-browser.mjs')
  exit $LASTEXITCODE
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
  Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
  $secure.Dispose()
}
