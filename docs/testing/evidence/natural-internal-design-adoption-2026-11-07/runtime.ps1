param(
    [ValidateSet('relay','api','browser')][string]$Mode,
    [ValidateSet('PLAN_CONTEXT_REPAIR','INTERNAL_DESIGN')][string]$Profile = 'PLAN_CONTEXT_REPAIR',
    [string]$BrowserStage = 'current'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$env:JAVA_HOME = 'E:\Delelop\Java\jdk-21'
$env:Path = 'E:\Delelop\apache-maven-3.9.14\bin;' + $env:Path
if ($Mode -eq 'relay') {
    $env:MODEL_API_KEY = [Environment]::GetEnvironmentVariable('MODEL_API_KEY','User')
    try { & node (Join-Path $PSScriptRoot 'evaluation.mjs') serve; exit $LASTEXITCODE }
    finally { Remove-Item Env:MODEL_API_KEY -ErrorAction SilentlyContinue }
}
if ($Mode -eq 'api') {
    # 测试服务仍使用既有合成账号及数据库，禁用迁移，实际调用只经本批计数转发。
    $env:API_KEY_ENCRYPTION_SECRET = [Environment]::GetEnvironmentVariable('API_KEY_ENCRYPTION_SECRET','User')
    foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_URL')) {
        $value = [Environment]::GetEnvironmentVariable($name,'User')
        if ($value) { [Environment]::SetEnvironmentVariable($name,$value,'Process') }
    }
    $env:MODEL_API_KEY = 'fixture'
    $env:MODEL_DEEPSEEK_API_KEY = 'fixture'
    $env:MODEL_DEEPSEEK_ENDPOINT = 'http://127.0.0.1:9078/chat/completions'
    $env:MODEL_DEFAULT_PROVIDER = 'deepseek'
    $env:NATURAL_ENHANCEMENT_PROFILE = $Profile
    $env:MODEL_MAX_TOKENS = '10000'
    $env:MODEL_TEMPERATURE = '0.2'
    $env:JAVA_TOOL_OPTIONS = '-Xmx512m -XX:MaxMetaspaceSize=256m'
    $env:MAVEN_OPTS = '-Xmx512m -XX:MaxMetaspaceSize=256m'
    & (Join-Path $repo 'scripts/manage-backend-local.ps1') -Action Restart -Port 9047 -SkipCompile
    exit $LASTEXITCODE
}
# 凭据只在当前进程内解密并交给一次性浏览器子进程，不输出或保存Cookie。
$secure = ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path $repo 'tmp/local-acceptance/credential.dpapi')).Trim())
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secure)
try {
    $env:POP_EVAL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
    $env:NODE_OPTIONS = '--max-old-space-size=768'
    & node (Join-Path $PSScriptRoot 'browser.mjs') $BrowserStage
    exit $LASTEXITCODE
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
    Remove-Item Env:POP_EVAL_PASSWORD -ErrorAction SilentlyContinue
    $secure.Dispose()
}
