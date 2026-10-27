[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage,
    [switch] $SkipCompile
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
$stageEvidence = Join-Path $PSScriptRoot $Stage
$evaluationRoot = Join-Path $repoRoot ('tmp/natural-context-rewrite-2026-10-27/' + $Stage)
$protocol = Get-Content -Raw -LiteralPath (Join-Path $stageEvidence 'protocol.json') | ConvertFrom-Json
$task = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'task-protocol.json') | ConvertFrom-Json
if ($protocol.status -ne 'FROZEN' -or $task.status -ne 'FROZEN' -or $task.selectedMaximumActualCalls -ne 200 -or $task.previousActualCalls -ne 158) { throw 'Frozen task and remaining 42-call allowance required.' }
foreach ($directory in @($PSScriptRoot, $stageEvidence, (Split-Path -Parent $evaluationRoot), $evaluationRoot)) {
    if (Test-Path -LiteralPath (Join-Path $directory 'closure.json')) { throw 'Batch is closed.' }
}
if (@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9013).Count) { throw 'Port 9013 is in use.' }
$jdkCandidates = @('E:\Delelop\Java\jdk-21\bin')
if ($env:JAVA_HOME) { $jdkCandidates += Join-Path $env:JAVA_HOME 'bin' }
$jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'javac.exe')) } | Select-Object -First 1
if (-not $jdk) { throw 'A real JDK directory with javac.exe is required; the Oracle launcher shim is not used.' }
# Maven也按真实JDK启动，避免其java.exe解析到Oracle公共启动器后挂起。
$env:JAVA_HOME = Split-Path -Parent $jdk
$env:PATH = $jdk + ';' + $env:PATH
foreach ($name in @('account.json','credential.dpapi')) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot ('tmp/local-acceptance/' + $name)))) { throw 'Canonical account fixture is missing.' }
}
if (-not $SkipCompile) {
    & mvn -o -q -DskipTests compile -f (Join-Path $repoRoot 'services/api/pom.xml')
    if ($LASTEXITCODE -ne 0) { throw 'Backend compile failed.' }
}
$runtimeClasses = Join-Path $evaluationRoot 'runtime/classes'
$runnerClasses = Join-Path $evaluationRoot 'runner'
if (Test-Path -LiteralPath $evaluationRoot) { throw 'Runtime directory already exists; retain evidence and choose a fresh frozen stage.' }
New-Item -ItemType Directory -Path $runtimeClasses,$runnerClasses,(Join-Path $evaluationRoot 'traces') -Force | Out-Null
Copy-Item -Path (Join-Path $repoRoot 'services/api/target/classes/*') -Destination $runtimeClasses -Recurse -Force
$dependencyPath = Join-Path $evaluationRoot 'dependency-classpath.txt'
& mvn -o -q dependency:build-classpath ('-Dmdep.outputFile=' + $dependencyPath) -f (Join-Path $repoRoot 'services/api/pom.xml')
if ($LASTEXITCODE -ne 0) { throw 'Offline dependency classpath failed.' }
$classpath = $runtimeClasses + ';' + [IO.File]::ReadAllText($dependencyPath).Trim()
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 21 -cp $classpath -d $runnerClasses (Join-Path $PSScriptRoot 'NaturalContextRewriteEvaluationServer.java')
if ($LASTEXITCODE -ne 0) { throw 'Evaluation server compile failed.' }
# 配置值只进入临时子进程环境，不打印、不放进命令参数或评测原件。
$names = @('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
$configuration = [IO.File]::ReadAllText((Join-Path $repoRoot 'services/api/src/main/resources/application.yml'))
foreach ($match in [regex]::Matches($configuration, '\$\{([A-Z][A-Z0-9_]+)')) { $names += $match.Groups[1].Value }
foreach ($name in ($names | Select-Object -Unique)) {
    $value = [Environment]::GetEnvironmentVariable($name, 'User')
    if (-not [string]::IsNullOrWhiteSpace($value)) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
}
if (-not $env:MODEL_API_KEY) { throw 'Model credential is missing from the local user environment.' }
$env:MODEL_PROVIDER_MODE = 'openai-compatible'
$env:MODEL_TEMPERATURE = [string]$protocol.parameters.temperature
$env:MODEL_MAX_TOKENS = [string]$protocol.parameters.generationMaxTokens
$env:MODEL_JSON_RESPONSE_FORMAT_ENABLED = 'true'
$env:MAP_REDUCE_SUMMARY_ENABLED = 'false'
$env:SEMANTIC_RETRIEVAL_ENABLED = 'false'
$env:EMAIL_REGISTRATION_ENABLED = 'false'
$env:SMS_ENABLED = 'false'
$files = @()
foreach ($file in (Get-ChildItem -LiteralPath $runtimeClasses,$runnerClasses -Recurse -File)) {
    $files += [pscustomobject]@{ path = $file.FullName; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$frozen = @((Join-Path $PSScriptRoot 'task-protocol.json'), (Join-Path $stageEvidence 'protocol.json'), (Join-Path $stageEvidence 'cases.json'), (Join-Path $stageEvidence 'jobs.json'), (Join-Path $PSScriptRoot 'NaturalContextRewriteEvaluationServer.java'), (Join-Path $repoRoot 'docs/testing/evidence/natural-multidimensional-2026-10-24/actual-call-ledger.json'))
foreach ($file in $frozen) { $files += [pscustomobject]@{ path = $file; sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() } }
if (Test-Path -LiteralPath (Join-Path $stageEvidence 'source-manifest.json')) { throw 'Stage manifest already frozen.' }
[IO.File]::WriteAllText((Join-Path $stageEvidence 'source-manifest.json'), (@{ gitHead = (& git rev-parse HEAD); files = $files } | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$arguments = @('-XX:ActiveProcessorCount=2','-Djava.net.preferIPv4Stack=true',('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $repoRoot 'tmp')),('-Dnatural.eval.accountFile=' + (Join-Path $repoRoot 'tmp/local-acceptance/account.json')),('-Dnatural.eval.priorLedger=' + (Join-Path $repoRoot 'docs/testing/evidence/natural-multidimensional-2026-10-24/actual-call-ledger.json')),('-Dnatural.eval.root=' + $evaluationRoot),('-Dnatural.eval.evidence=' + $stageEvidence),'-cp',($runnerClasses + ';' + $classpath),'NaturalContextRewriteEvaluationServer')
$process = Start-Process -FilePath (Join-Path $jdk 'java.exe') -ArgumentList $arguments -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationRoot 'server.out.log') -RedirectStandardError (Join-Path $evaluationRoot 'server.err.log')
[IO.File]::WriteAllText((Join-Path $evaluationRoot 'process.json'), (@{pid=$process.Id;port=9013;process='NaturalContextRewriteEvaluationServer';stage=$Stage;workspace=$repoRoot;startedAt=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output ('Started isolated evaluation process pid=' + $process.Id + '; port=9013; no model job has been submitted.')
