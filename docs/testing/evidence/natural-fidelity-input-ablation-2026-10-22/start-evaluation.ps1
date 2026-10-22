#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$canonicalRoot = 'E:\MyProject\prompt-optimizer-platform'
$evaluationRoot = Join-Path $repoRoot 'tmp/natural-fidelity-input-ablation-20261022'
$runtimeClasses = Join-Path $evaluationRoot 'runtime/classes'
$runnerClasses = Join-Path $evaluationRoot 'runner'
if (Get-NetTCPConnection -State Listen -LocalPort 9003 -ErrorAction SilentlyContinue) { throw '9003已占用，不接管其他会话进程。' }
if (Test-Path -LiteralPath $runtimeClasses) { throw '运行副本已存在，不覆盖已冻结版本。' }
New-Item -ItemType Directory -Path $runtimeClasses,$runnerClasses,(Join-Path $evaluationRoot 'traces') -Force | Out-Null
Copy-Item -Path (Join-Path $repoRoot 'services/api/target/classes/*') -Destination $runtimeClasses -Recurse
$dependencyFile = Get-ChildItem -LiteralPath (Join-Path $canonicalRoot 'tmp/backend-local-managed') -Filter 'runtime-classpath.txt' -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $dependencyFile) { throw '缺少既有运行依赖清单。' }
# 只复用依赖JAR，避免其他会话的产品classes成为本轮候选的兜底实现。
$dependencies = ([IO.File]::ReadAllText($dependencyFile.FullName).Trim().Split(';') | Where-Object { $_.EndsWith('.jar') }) -join ';'
$classpath = $runtimeClasses + ';' + $dependencies
[IO.File]::WriteAllText((Join-Path $evaluationRoot 'runtime-classpath.txt'), $classpath, [Text.UTF8Encoding]::new($false))
& 'E:\Delelop\Java\jdk-21\bin\javac.exe' -encoding UTF-8 --release 21 -cp $classpath -d $runnerClasses (Join-Path $PSScriptRoot 'NaturalFidelityInputAblationEvaluationServer.java')
if ($LASTEXITCODE -ne 0) { throw '评测入口编译失败，未启动进程或调用模型。' }

# 环境值只继承到本次子进程，不读取.env或生产配置，不打印密钥，也不写入命令行。
$names = @('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
$configuration = [IO.File]::ReadAllText((Join-Path $repoRoot 'services/api/src/main/resources/application.yml'))
foreach ($match in [regex]::Matches($configuration, '\$\{([A-Z][A-Z0-9_]+)')) { $names += $match.Groups[1].Value }
foreach ($name in ($names | Select-Object -Unique)) {
    $value = [Environment]::GetEnvironmentVariable($name, 'User')
    if (-not [string]::IsNullOrWhiteSpace($value)) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
}
if (-not $env:MODEL_API_KEY) { throw '既有用户环境缺少模型密钥，未创建替代配置。' }
$env:MODEL_PROVIDER_MODE='openai-compatible'
$env:MODEL_TEMPERATURE='0.2'
$env:MODEL_MAX_TOKENS='8192'
$env:MODEL_JSON_RESPONSE_FORMAT_ENABLED='true'
$env:MAP_REDUCE_SUMMARY_ENABLED='false'
$env:SEMANTIC_RETRIEVAL_ENABLED='false'
$env:EMAIL_REGISTRATION_ENABLED='false'
$env:SMS_ENABLED='false'

& 'E:\Delelop\Java\jdk-21\bin\java.exe' -cp $classpath --source 21 (Join-Path $PSScriptRoot 'DumpFidelityInstructions.java') (Join-Path $PSScriptRoot 'fidelity-instruction.txt')
if ($LASTEXITCODE -ne 0) { throw 'Cannot freeze fidelity instruction.' }
$files = @()
$tracked = & git -C $repoRoot ls-files --cached --others --exclude-standard 'services/api/src/main'
if ($LASTEXITCODE -ne 0) { throw '不能读取本轮隔离工作树源码清单。' }
foreach ($relative in $tracked) {
    $files += [pscustomobject]@{path=$relative; sha256=(Get-FileHash -LiteralPath (Join-Path $repoRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant()}
}
foreach ($name in @('cases.json','protocol.json','NaturalFidelityInputAblationEvaluationServer.java','run-evaluation.mjs','start-evaluation.ps1','invoke-evaluation.ps1','offline-validation.json','review-fixtures.json','fidelity-instruction.txt','FidelityFixtureCheck.java')) {
    $relative = 'docs/testing/evidence/natural-fidelity-input-ablation-2026-10-22/' + $name
    $files += [pscustomobject]@{path=$relative; sha256=(Get-FileHash -LiteralPath (Join-Path $repoRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant()}
}
Get-ChildItem -LiteralPath $runtimeClasses -Recurse -File | ForEach-Object {
    $relative=[IO.Path]::GetRelativePath($repoRoot,$_.FullName).Replace('\','/')
    $files += [pscustomobject]@{path=$relative; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$manifestPath=Join-Path $PSScriptRoot 'source-manifest.json'
if (Test-Path -LiteralPath $manifestPath) { throw '清单已经冻结，不能覆盖。' }
$manifest=[ordered]@{gitHead=(& git -C $repoRoot rev-parse HEAD); baselineGitHead='8195626e'; date='2026-10-22'; files=$files}
[IO.File]::WriteAllText($manifestPath,($manifest | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$daily=@(Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in @(9000,9002,5173,5175,5432,6379) } |
    Select-Object LocalPort,OwningProcess -Unique)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'environment-before.json'),($daily | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$socketPath=Join-Path $evaluationRoot 'jdk-sockets'
if (Test-Path -LiteralPath $socketPath) { throw '本次JDK socket回退目录必须不存在。' }
$accountFile=Join-Path $canonicalRoot 'tmp/local-acceptance/account.json'
$arguments=@('-XX:ActiveProcessorCount=2',('-Djdk.net.unixdomain.tmpdir='+$socketPath),('-Dnatural.eval.accountFile='+$accountFile),
    '-cp',('"'+$runnerClasses+';'+$classpath+'"'),'NaturalFidelityInputAblationEvaluationServer')
$process=Start-Process -FilePath 'E:\Delelop\Java\jdk-21\bin\java.exe' -ArgumentList $arguments -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $evaluationRoot 'server.out.log') -RedirectStandardError (Join-Path $evaluationRoot 'server.err.log')
$ownership=[ordered]@{pid=$process.Id;port=9003;process='NaturalFidelityInputAblationEvaluationServer';workspace=$repoRoot;startedAt=[DateTimeOffset]::Now.ToString('o')}
[IO.File]::WriteAllText((Join-Path $evaluationRoot 'process.json'),($ownership | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$ready=$false
for ($attempt=0;$attempt -lt 30;$attempt++) {
    try { $health=Invoke-RestMethod -Uri 'http://127.0.0.1:9003/api/v1/health' -TimeoutSec 1; if ($health.data.status -eq 'UP') { $ready=$true;break } } catch {}
    Start-Sleep -Milliseconds 1000
}
[pscustomobject]@{pid=$process.Id;ready=$ready;modelCalls=0;accountCreationAllowed=$false} | ConvertTo-Json -Compress
if (-not $ready) { throw '临时评测进程未就绪，先检查启动记录；未调用模型。' }
