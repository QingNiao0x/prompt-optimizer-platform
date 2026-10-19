[CmdletBinding()]
param([switch] $Correction)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$evaluationRoot = Join-Path $repoRoot 'tmp/natural-task-eval-20261019'
$runtimeClasses = Join-Path $evaluationRoot $(if ($Correction) { 'runtime-candidate2/classes' } else { 'runtime/classes' })
$runnerClasses = Join-Path $evaluationRoot $(if ($Correction) { 'runner-candidate2' } else { 'runner' })
if (Get-NetTCPConnection -State Listen -LocalPort 9002 -ErrorAction SilentlyContinue) { throw '评测端口已占用，未接管任何进程。' }
if (Test-Path -LiteralPath $runtimeClasses) { throw '运行副本已存在，禁止静默覆盖冻结版本。' }
New-Item -ItemType Directory -Path $runtimeClasses,$runnerClasses,(Join-Path $evaluationRoot 'traces') -Force | Out-Null
Copy-Item -Path (Join-Path $repoRoot 'services/api/target/classes/*') -Destination $runtimeClasses -Recurse
$classpathFile = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'tmp/backend-local-managed') -Filter 'runtime-classpath.txt' -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $classpathFile) { throw '缺少既有运行依赖清单。' }
$classpath = $runtimeClasses + ';' + [IO.File]::ReadAllText($classpathFile.FullName).Trim()
[IO.File]::WriteAllText((Join-Path $evaluationRoot $(if ($Correction) { 'runtime-classpath-candidate2.txt' } else { 'runtime-classpath.txt' })), $classpath, [Text.UTF8Encoding]::new($false))
& 'E:\Delelop\Java\jdk-21\bin\javac.exe' -encoding UTF-8 --release 21 -cp $classpath -d $runnerClasses (Join-Path $PSScriptRoot 'NaturalTaskEvaluationServer.java')
if ($LASTEXITCODE -ne 0) { throw '评测入口编译失败，未启动进程或调用模型。' }

# 密钥仅从既有用户环境继承到子进程，不读取受保护配置、不写磁盘或命令行。
$names = @('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
Get-ChildItem -LiteralPath (Join-Path $repoRoot 'services/api/src/main/resources') -Filter 'application*.yml' | ForEach-Object {
    $configuration = [IO.File]::ReadAllText($_.FullName)
    foreach ($match in [regex]::Matches($configuration, '\$\{([A-Z][A-Z0-9_]+)')) { $names += $match.Groups[1].Value }
}
foreach ($name in ($names | Select-Object -Unique)) {
    $value = [Environment]::GetEnvironmentVariable($name, 'User')
    if (-not [string]::IsNullOrWhiteSpace($value)) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
}
if (-not $env:MODEL_API_KEY) { throw '既有用户环境缺少模型密钥，未创建替代配置。' }
$env:MODEL_PROVIDER_MODE = 'openai-compatible'
$env:MODEL_TEMPERATURE = '0.2'
$env:MODEL_MAX_TOKENS = '8192'
$env:MODEL_JSON_RESPONSE_FORMAT_ENABLED = 'true'
$env:MAP_REDUCE_SUMMARY_ENABLED = 'false'
$env:SEMANTIC_RETRIEVAL_ENABLED = 'false'
$env:EMAIL_REGISTRATION_ENABLED = 'false'
$env:SMS_ENABLED = 'false'

# 冻结源码、题目与独立运行副本。哈希清单不含环境值、账号引用或凭据。
$files = @()
$tracked = & git -C $repoRoot ls-files 'services/api/src/main'
foreach ($relative in $tracked) {
    $absolute = Join-Path $repoRoot $relative
    $files += [pscustomobject]@{path=$relative; sha256=(Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToLowerInvariant()}
}
foreach ($name in @('cases.json',$(if ($Correction) { 'protocol-candidate2.json' } else { 'protocol.json' }),'NaturalTaskEvaluationServer.java','run-evaluation.mjs','start-evaluation.ps1','invoke-evaluation.ps1')) {
    $relative = 'docs/testing/evidence/natural-task-generalization-2026-10-19/' + $name
    $files += [pscustomobject]@{path=$relative; sha256=(Get-FileHash -LiteralPath (Join-Path $repoRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$manifest = [ordered]@{gitHead=(& git -C $repoRoot rev-parse HEAD); date='2026-10-19'; files=$files}
$manifestPath = Join-Path $PSScriptRoot $(if ($Correction) { 'source-manifest-candidate2.json' } else { 'source-manifest.json' })
if (Test-Path -LiteralPath $manifestPath) { throw '源码清单已冻结，禁止静默覆盖。' }
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))

# 此机JDK的AF_UNIX选择器需要TCP回退；目录保持不存在，仅影响本次子进程。
$socketPath = Join-Path $evaluationRoot 'jdk-sockets'
if (Test-Path -LiteralPath $socketPath) { throw '测试socket回退目录必须不存在。' }
$arguments = @('-XX:ActiveProcessorCount=2', ('-Djdk.net.unixdomain.tmpdir=' + $socketPath),
    '-cp', ('"' + $runnerClasses + ';' + $classpath + '"'), 'NaturalTaskEvaluationServer')
if ($Correction) { $arguments += 'candidate2' }
$process = Start-Process -FilePath 'E:\Delelop\Java\jdk-21\bin\java.exe' -ArgumentList $arguments -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationRoot $(if ($Correction) { 'server-candidate2.out.log' } else { 'server.out.log' })) -RedirectStandardError (Join-Path $evaluationRoot $(if ($Correction) { 'server-candidate2.err.log' } else { 'server.err.log' }))
$ownership = [ordered]@{pid=$process.Id; port=9002; process='NaturalTaskEvaluationServer'; workspace=$repoRoot; startedAt=(Get-Date).ToString('o')}
[IO.File]::WriteAllText((Join-Path $evaluationRoot $(if ($Correction) { 'process-candidate2.json' } else { 'process.json' })), ($ownership | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:9002/api/v1/health' -TimeoutSec 1
        if ($health.data.status -eq 'UP') { $ready = $true; break }
    } catch {}
    Start-Sleep -Milliseconds 1000
}
[pscustomobject]@{pid=$process.Id; ready=$ready; modelCalls=0; accountCreationAllowed=$false} | ConvertTo-Json -Compress
if (-not $ready) { throw '评测进程尚未就绪，未调用模型；按进程归属记录定位。' }
