param(
    [ValidateSet('Validate','Collect','Execute')][string]$Action = 'Validate',
    [string]$InputPath = 'tmp/confirmation-boundary-20261009/cases.json',
    [Parameter(Mandatory=$true)][string]$RunDirectory,
    [string]$ClassesPath = 'tmp/confirmation-boundary-20261009/target/classes',
    [string]$ClasspathFile = 'tmp/backend-local-managed/2026-10-08T11-36-35-858Z-31d87dcc/runtime-classpath.txt'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$taskRun = [IO.Path]::GetFullPath((Join-Path $taskRepo $RunDirectory))
if (-not $taskRun.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Run directory must be a new child of this evaluation folder.' }
$taskClasses = (Resolve-Path -LiteralPath (Join-Path $taskRepo $ClassesPath)).ProviderPath
$taskCpFile = (Resolve-Path -LiteralPath (Join-Path $taskRepo $ClasspathFile)).ProviderPath
if (-not $taskClasses.StartsWith($taskRepo + '\',[StringComparison]::OrdinalIgnoreCase) -or -not $taskCpFile.StartsWith($taskRepo + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Candidate classes and classpath must remain in the repository.' }
$taskClasspath = $taskClasses + ';' + (Get-Content -LiteralPath $taskCpFile -Raw).Trim()

# 只把本轮需要的 DeepSeek 路由变量放进子进程环境；不读 .env，不打印值，不注入 TokenHub 凭据。
$taskEnvironmentNames = @('MODEL_API_KEY','MODEL_DEEPSEEK_API_KEY','MODEL_DEEPSEEK_ENDPOINT','MODEL_DEEPSEEK_MODELS','MODEL_DEEPSEEK_NAME','MODEL_DEEPSEEK_PROVIDER_NAME','MODEL_MULTI_PROVIDER_ENABLED','MODEL_DEFAULT_PROVIDER')
$taskOriginalEnvironment = @{}
foreach ($taskName in $taskEnvironmentNames) {
    $taskOriginalEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName,'Process')
    $taskUserValue = [Environment]::GetEnvironmentVariable($taskName,'User')
    if (-not [string]::IsNullOrWhiteSpace($taskUserValue)) { [Environment]::SetEnvironmentVariable($taskName,$taskUserValue,'Process') }
}
try {
    # 本机 Oracle javapath 启动代理可能等待；优先真实 JDK 21 可执行文件，避免把等待误判为编译成功。
    $taskJdk = [Environment]::GetEnvironmentVariable('JAVA_HOME','Process')
    if ([string]::IsNullOrWhiteSpace($taskJdk) -and (Test-Path 'C:\Program Files\Java\latest\jdk-21\bin\javac.exe')) { $taskJdk = 'C:\Program Files\Java\latest\jdk-21' }
    $taskJavac = if ([string]::IsNullOrWhiteSpace($taskJdk)) { (Get-Command javac -ErrorAction Stop).Source } else { Join-Path $taskJdk 'bin/javac.exe' }
    $taskJava = if ([string]::IsNullOrWhiteSpace($taskJdk)) { (Get-Command java -ErrorAction Stop).Source } else { Join-Path $taskJdk 'bin/java.exe' }
    $taskVersion = (& $taskJavac -version 2>&1 | Out-String).Trim()
    if ($taskVersion -notmatch '^javac 21(?:\.|$)') { throw 'JDK 21 required.' }
    $taskBuild = Join-Path $taskRoot ('build-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskBuild | Out-Null
    & $taskJavac -encoding UTF-8 --release 21 -cp $taskClasspath -d $taskBuild (Join-Path $taskRoot 'ConfirmationBoundaryRealEvaluation.java')
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation compilation failed.' }
    $taskNio = Join-Path $taskRoot 'nio'
    New-Item -ItemType Directory -Path $taskNio -Force | Out-Null
    Push-Location -LiteralPath $taskRepo
    try {
        & $taskJava '-Xms32m' '-Xmx384m' '-XX:ActiveProcessorCount=2' ('-Djdk.net.unixdomain.tmpdir=' + $taskNio) -cp ($taskBuild + ';' + $taskClasspath) com.promptoptimizer.enhancement.service.impl.ConfirmationBoundaryRealEvaluation $Action $InputPath $taskRun $taskClasses
        if ($LASTEXITCODE -ne 0) { throw 'Evaluation stopped; inspect safe status codes and preserved evidence.' }
    } finally { Pop-Location }
} finally {
    foreach ($taskName in $taskEnvironmentNames) { [Environment]::SetEnvironmentVariable($taskName,$taskOriginalEnvironment[$taskName],'Process') }
}
