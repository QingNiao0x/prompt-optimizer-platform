#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
$evidence = Join-Path $PSScriptRoot $Stage
$evaluationRoot = Join-Path $repoRoot ('tmp/natural-preparation-routing-2026-10-24/' + $Stage)
$runtimeClasses = Join-Path $evaluationRoot 'runtime/classes'
$runnerClasses = Join-Path $evaluationRoot 'runner'
& node (Join-Path $PSScriptRoot 'validate-stage.mjs') $Stage
if ($LASTEXITCODE -ne 0) { throw '冻结协议离线校验失败，未启动。' }
$protocol = Get-Content -LiteralPath (Join-Path $evidence 'protocol.json') -Raw | ConvertFrom-Json
if ($protocol.status -ne 'FROZEN') { throw '本阶段协议尚未冻结。' }
foreach ($directory in @($PSScriptRoot, $evidence, (Split-Path -Parent $evaluationRoot), $evaluationRoot)) {
    if (Test-Path -LiteralPath (Join-Path $directory 'closure.json')) { throw '任务或阶段已经闭批，不得重新启动。' }
}
# 全部监听查询必须成功，权限错误不能被当作端口空闲。
if (@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9003).Count) { throw '9003已占用，不接管其他会话进程。' }
if (Test-Path -LiteralPath $runtimeClasses) { throw '运行副本已存在，不覆盖已冻结版本。' }
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'tmp/local-acceptance/account.json')) -or
    -not (Test-Path -LiteralPath (Join-Path $repoRoot 'tmp/local-acceptance/credential.dpapi'))) {
    throw '固定合成账号引用或凭据缺失，不创建替代账号。'
}
New-Item -ItemType Directory -Path $runtimeClasses,$runnerClasses,(Join-Path $evaluationRoot 'traces') -Force | Out-Null
Copy-Item -Path (Join-Path $repoRoot 'services/api/target/classes/*') -Destination $runtimeClasses -Recurse
$dependencyFile = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'tmp/backend-local-managed') -Filter runtime-classpath.txt -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $dependencyFile) { throw '缺少既有运行依赖清单。' }
$dependencies = ([IO.File]::ReadAllText($dependencyFile.FullName).Trim().Split(';') | Where-Object { $_.EndsWith('.jar') }) -join ';'
$classpath = $runtimeClasses + ';' + $dependencies
[IO.File]::WriteAllText((Join-Path $evaluationRoot 'runtime-classpath.txt'),$classpath,[Text.UTF8Encoding]::new($false))
$jdk = 'E:\Delelop\Java\jdk-21\bin'
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 21 -cp $classpath -d $runnerClasses (Join-Path $PSScriptRoot 'NaturalPreparationRoutingEvaluationServer.java')
if ($LASTEXITCODE -ne 0) { throw '评测入口编译失败；未启动或调用模型。' }

# 只继承现有用户环境，既不读取.env/生产配置，也不打印配置值。
$names = @('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
$configuration = [IO.File]::ReadAllText((Join-Path $repoRoot 'services/api/src/main/resources/application.yml'))
foreach ($match in [regex]::Matches($configuration, '\$\{([A-Z][A-Z0-9_]+)')) { $names += $match.Groups[1].Value }
foreach ($name in ($names | Select-Object -Unique)) {
    $value = [Environment]::GetEnvironmentVariable($name,'User')
    if (-not [string]::IsNullOrWhiteSpace($value)) { [Environment]::SetEnvironmentVariable($name,$value,'Process') }
}
if (-not $env:MODEL_API_KEY) { throw '既有用户环境缺少模型密钥，未创建替代配置。' }
$env:MODEL_PROVIDER_MODE='openai-compatible'
$env:MODEL_TEMPERATURE=[string]$protocol.parameters.temperature
$env:MODEL_MAX_TOKENS=[string]$protocol.parameters.generationMaxTokens
$env:MODEL_JSON_RESPONSE_FORMAT_ENABLED='true'
$env:MAP_REDUCE_SUMMARY_ENABLED='false'
$env:SEMANTIC_RETRIEVAL_ENABLED='false'
$env:EMAIL_REGISTRATION_ENABLED='false'
$env:SMS_ENABLED='false'
$dumpInstructions=Join-Path $PSScriptRoot 'DumpNaturalInstructions.java'
& (Join-Path $jdk 'java.exe') -cp $classpath --source 21 $dumpInstructions $evidence @($protocol.versions)
if ($LASTEXITCODE -ne 0) { throw '实际指导冻结失败。' }
$files = @()
$tracked = & git -C $repoRoot ls-files --cached --others --exclude-standard 'services/api/src/main'
if ($LASTEXITCODE -ne 0) { throw '不能读取源码清单。' }
foreach ($relative in $tracked) {
    $files += [pscustomobject]@{path=$relative;sha256=(Get-FileHash -LiteralPath (Join-Path $repoRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$frozen = @((Join-Path $PSScriptRoot 'task-protocol.json'), $dumpInstructions)
$frozen += @(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object Extension -in @('.java','.mjs','.ps1','.py') | ForEach-Object FullName)
$frozen += @('cases.json','protocol.json','offline-validation.json','compiled-default-version.txt') | ForEach-Object { Join-Path $evidence $_ }
if ($Stage -eq 'heldout') {
    $frozen += @((Join-Path $PSScriptRoot 'development/regression-gate.json'),(Join-Path $PSScriptRoot 'development/closure.json'),(Join-Path $evidence 'release-manifest.json'))
}
$reuseManifestPath=Join-Path $evidence 'reuse-manifest.json'
if (Test-Path -LiteralPath $reuseManifestPath) {
    # 复用原件与复制件在新批次同样冻结；历史traces仍留原批，不复制或重新计费。
    $reuseManifest=Get-Content -LiteralPath $reuseManifestPath -Raw | ConvertFrom-Json
    $frozen += $reuseManifestPath
    $frozen += @($reuseManifest.files | ForEach-Object { Join-Path $repoRoot $_.path })
}
foreach ($version in $protocol.versions) { $frozen += (Join-Path $evidence ('enhancement-v'+$version+'.txt')),(Join-Path $evidence ('planning-v'+$version+'.txt')),(Join-Path $evidence ('preparation-v'+$version+'.txt')) }
$frozen += @(Get-ChildItem -LiteralPath $runtimeClasses,$runnerClasses -Recurse -File | ForEach-Object FullName)
foreach ($file in $frozen) {
    $files += [pscustomobject]@{path=[IO.Path]::GetRelativePath($repoRoot,$file).Replace('\','/');sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$manifestPath=Join-Path $evidence 'source-manifest.json'
if (Test-Path -LiteralPath $manifestPath) { throw '清单已经冻结，不覆盖。' }
$manifest=[ordered]@{gitHead=(& git -C $repoRoot rev-parse HEAD);stage=$Stage;frozenAt=[DateTimeOffset]::Now.ToString('o');files=$files}
[IO.File]::WriteAllText($manifestPath,($manifest | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$daily=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -in @(9000,9002,5173,5175,5432,6379) } | Select-Object LocalPort,OwningProcess -Unique)
[IO.File]::WriteAllText((Join-Path $evidence 'environment-before.json'),($daily | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$socketPath=Join-Path $evaluationRoot 'jdk-sockets'
if (Test-Path -LiteralPath $socketPath) { throw '本次JDK socket目录必须不存在。' }
$accountFile=Join-Path $repoRoot 'tmp/local-acceptance/account.json'
if (-not (Test-Path -LiteralPath $accountFile)) { throw '缺少固定合成账号引用，不创建替代账号。' }
$arguments=@('-XX:ActiveProcessorCount=2',('-Djdk.net.unixdomain.tmpdir='+$socketPath),('-Dnatural.eval.accountFile='+$accountFile),
    ('-Dnatural.eval.root='+$evaluationRoot),('-Dnatural.eval.evidence='+$evidence),'-cp',('"'+$runnerClasses+';'+$classpath+'"'),'NaturalPreparationRoutingEvaluationServer')
$process=Start-Process -FilePath (Join-Path $jdk 'java.exe') -ArgumentList $arguments -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $evaluationRoot 'server.out.log') -RedirectStandardError (Join-Path $evaluationRoot 'server.err.log')
$identity=Get-CimInstance Win32_Process -Filter ('ProcessId='+$process.Id) -ErrorAction Stop
if (-not $identity -or $identity.ExecutablePath -ne (Join-Path $jdk 'java.exe') -or
    -not $identity.CommandLine.Contains('NaturalPreparationRoutingEvaluationServer') -or
    -not $identity.CommandLine.Contains($evaluationRoot)) { throw '新启动进程身份不匹配，不发送评测。' }
$ownership=[ordered]@{pid=$process.Id;port=9003;process='NaturalPreparationRoutingEvaluationServer';stage=$Stage;workspace=$repoRoot;startedAt=[DateTimeOffset]::Now.ToString('o');processStartedAt=$identity.CreationDate.ToUniversalTime().ToString('o');executable=(Join-Path $jdk 'java.exe')}
[IO.File]::WriteAllText((Join-Path $evaluationRoot 'process.json'),($ownership | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$ready=$false
for ($attempt=0;$attempt -lt 30;$attempt++) {
    try { $health=Invoke-RestMethod -Uri 'http://127.0.0.1:9003/api/v1/health' -TimeoutSec 1; if ($health.data.status -eq 'UP') { $ready=$true;break } } catch {}
    Start-Sleep -Milliseconds 1000
}
[pscustomobject]@{pid=$process.Id;ready=$ready;modelCalls=0;accountCreationAllowed=$false} | ConvertTo-Json -Compress
if (-not $ready) { throw '临时进程未就绪，先检查启动记录；未调用模型。' }
$listeners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9003)
if (-not $listeners.Count -or @($listeners | Where-Object OwningProcess -ne $process.Id).Count) {
    throw '9003监听归属与本阶段新进程不一致，不执行评测。'
}
