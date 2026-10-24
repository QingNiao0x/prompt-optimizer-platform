[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
$evidenceRoot = $PSScriptRoot
$evidence = Join-Path $evidenceRoot $Stage
$evaluationRoot = Join-Path $repoRoot ('tmp/natural-grounded-completion-2026-10-24/' + $Stage)
$runtimeClasses = Join-Path $evaluationRoot 'runtime/classes'
$runnerClasses = Join-Path $evaluationRoot 'runner'
$protocol = Get-Content -LiteralPath (Join-Path $evidence 'protocol.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$task = Get-Content -LiteralPath (Join-Path $evidenceRoot 'task-protocol.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($protocol.status -ne 'FROZEN' -or $task.status -ne 'FROZEN') { throw 'Protocol is not frozen.' }
if ($protocol.taskMaximumActualCalls -ne $task.selectedMaximumActualCalls -or $task.selectedMaximumActualCalls -gt 100) {
    throw 'Call ceiling does not match the authorized maximum of 100.'
}
foreach ($directory in @($evidenceRoot, $evidence, (Split-Path -Parent $evaluationRoot), $evaluationRoot)) {
    if (Test-Path -LiteralPath (Join-Path $directory 'closure.json')) { throw 'Batch is already closed.' }
}
if (@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9003).Count) { throw 'Port 9003 is already in use.' }
if (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'tmp/local-acceptance/account.json')) -or
    -not (Test-Path -LiteralPath (Join-Path $repoRoot 'tmp/local-acceptance/credential.dpapi'))) {
    throw 'Canonical account reference or credential is missing.'
}
& mvn -q -DskipTests compile -f (Join-Path $repoRoot 'services/api/pom.xml')
if ($LASTEXITCODE -ne 0) { throw 'Backend compile failed.' }
New-Item -ItemType Directory -Path $runtimeClasses,$runnerClasses,(Join-Path $evaluationRoot 'traces') -Force | Out-Null
Copy-Item -Path (Join-Path $repoRoot 'services/api/target/classes/*') -Destination $runtimeClasses -Recurse -Force
$dependencyFile = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'tmp/backend-local-managed') -Filter runtime-classpath.txt -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $dependencyFile) { throw 'Runtime classpath list is missing.' }
$dependencies = ([IO.File]::ReadAllText($dependencyFile.FullName).Trim().Split(';') | Where-Object { $_.EndsWith('.jar') }) -join ';'
$classpath = $runtimeClasses + ';' + $dependencies
$jdk = 'E:\Delelop\Java\jdk-21\bin'
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 21 -cp $classpath -d $runnerClasses (Join-Path $PSScriptRoot 'NaturalGroundedCompletionEvaluationServer.java')
if ($LASTEXITCODE -ne 0) { throw 'Evaluation entry failed to compile.' }
$names = @('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
$configuration = [IO.File]::ReadAllText((Join-Path $repoRoot 'services/api/src/main/resources/application.yml'))
foreach ($match in [regex]::Matches($configuration, '\$\{([A-Z][A-Z0-9_]+)')) { $names += $match.Groups[1].Value }
foreach ($name in ($names | Select-Object -Unique)) {
    $value = [Environment]::GetEnvironmentVariable($name,'User')
    if (-not [string]::IsNullOrWhiteSpace($value)) { [Environment]::SetEnvironmentVariable($name,$value,'Process') }
}
if (-not $env:MODEL_API_KEY) { throw 'Model credential is missing from the user environment.' }
$env:MODEL_PROVIDER_MODE = 'openai-compatible'
$env:MODEL_TEMPERATURE = [string]$protocol.parameters.temperature
$env:MODEL_MAX_TOKENS = [string]$protocol.parameters.generationMaxTokens
$env:MODEL_JSON_RESPONSE_FORMAT_ENABLED = 'true'
$env:MAP_REDUCE_SUMMARY_ENABLED = 'false'
$env:SEMANTIC_RETRIEVAL_ENABLED = 'false'
$env:EMAIL_REGISTRATION_ENABLED = 'false'
$env:SMS_ENABLED = 'false'
$files = @()
$tracked = & git -C $repoRoot ls-files --cached --others --exclude-standard 'services/api/src/main'
if ($LASTEXITCODE -ne 0) { throw 'Cannot list source files.' }
foreach ($relative in $tracked) {
    $files += [pscustomobject]@{ path = ($relative -replace '\\','/'); sha256 = (Get-FileHash -LiteralPath (Join-Path $repoRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$frozen = @(
    (Join-Path $evidenceRoot 'task-protocol.json'),
    (Join-Path $evidenceRoot 'hypothesis.json'),
    (Join-Path $evidenceRoot 'NaturalGroundedCompletionEvaluationServer.java'),
    (Join-Path $evidenceRoot 'run-evaluation.mjs'),
    (Join-Path $evidenceRoot 'evaluation-inputs.mjs'),
    (Join-Path $evidenceRoot 'reuse-evidence.mjs'),
    (Join-Path $evidenceRoot 'start-evaluation.ps1'),
    (Join-Path $evidenceRoot 'invoke-evaluation.ps1'),
    (Join-Path $evidence 'protocol.json'),
    (Join-Path $evidence 'cases.json')
)
foreach ($file in $frozen) {
    $relativePath = $file.Substring($repoRoot.Length).TrimStart('\').TrimStart('/').Replace('\','/')
    $files += [pscustomobject]@{ path = $relativePath; sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$manifestPath = Join-Path $evidence 'source-manifest.json'
if (Test-Path -LiteralPath $manifestPath) { throw 'Manifest is already frozen.' }
$manifest = [ordered]@{ gitHead = (& git -C $repoRoot rev-parse HEAD); stage = $Stage; frozenAt = [DateTimeOffset]::Now.ToString('o'); files = $files }
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$accountFile = Join-Path $repoRoot 'tmp/local-acceptance/account.json'
$socketPath = Join-Path $evaluationRoot 'jdk-sockets'
$arguments = @(
    '-XX:ActiveProcessorCount=2',
    ('-Djdk.net.unixdomain.tmpdir=' + $socketPath),
    ('-Dnatural.eval.accountFile=' + $accountFile),
    ('-Dnatural.eval.root=' + $evaluationRoot),
    ('-Dnatural.eval.evidence=' + $evidence),
    '-cp', ($runnerClasses + ';' + $classpath),
    'NaturalGroundedCompletionEvaluationServer'
)
$process = Start-Process -FilePath (Join-Path $jdk 'java.exe') -ArgumentList $arguments -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $evaluationRoot 'server.out.log') -RedirectStandardError (Join-Path $evaluationRoot 'server.err.log')
Start-Sleep -Seconds 1
$identity = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $process.Id) -ErrorAction Stop
if (-not $identity -or -not $identity.CommandLine.Contains('NaturalGroundedCompletionEvaluationServer')) { throw 'Evaluation process identity mismatch.' }
$ownership = [ordered]@{
    pid = $process.Id; port = 9003; process = 'NaturalGroundedCompletionEvaluationServer'; stage = $Stage; workspace = $repoRoot;
    startedAt = [DateTimeOffset]::Now.ToString('o'); processStartedAt = $identity.CreationDate.ToUniversalTime().ToString('o');
    executable = (Join-Path $jdk 'java.exe')
}
[IO.File]::WriteAllText((Join-Path $evaluationRoot 'process.json'), ($ownership | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$ready = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:9003/api/v1/health' -TimeoutSec 2
        if ($health.data.status -eq 'UP') { $ready = $true; break }
    } catch {}
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Evaluation process did not become ready.' }
Write-Output ('ready pid=' + $process.Id)
