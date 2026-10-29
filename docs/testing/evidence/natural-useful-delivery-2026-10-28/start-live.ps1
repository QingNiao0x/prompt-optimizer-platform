[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage,
    [switch] $SkipCompile
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Set-Location -LiteralPath $repoRoot
$stageEvidence = Join-Path $PSScriptRoot $Stage
$evaluationRoot = Join-Path $repoRoot ('tmp/natural-useful-delivery-2026-10-28/' + $Stage)
$protocol = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $stageEvidence 'protocol.json') | ConvertFrom-Json
$task = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $PSScriptRoot 'task-protocol.json') | ConvertFrom-Json
if ($protocol.status -ne 'FROZEN' -or $task.status -ne 'FROZEN' -or $task.selectedMaximumActualCalls -ne 500 -or $task.previousActualCalls -ne 0) { throw 'Frozen independent 500-call task required.' }
foreach ($directory in @($PSScriptRoot, $stageEvidence, (Split-Path -Parent $evaluationRoot), $evaluationRoot)) {
    if (Test-Path -LiteralPath (Join-Path $directory 'closure.json')) { throw 'Batch is closed.' }
}
if (@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 9014).Count) { throw 'Port 9014 is in use.' }
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
$nativeSnapshotRoot = $null
if ($protocol.modelSelectionProfile) {
    $nativeBaselineStage = switch ($protocol.modelSelectionProfile.name) {
        'OFFICIAL_MODEL_SELECTION_ABLATION_V1' { 'registry50-development' }
        'NATIVE_PRO52_PREPARE_MECHANISM_V1' { 'registry52-plan-completion' }
        default { throw 'Unknown native model-selection profile.' }
    }
    if ($protocol.modelSelectionProfile.baselineStage -ne $nativeBaselineStage -or $protocol.modelId -ne 'deepseek:deepseek-v4-pro') { throw 'Unknown native model-selection profile.' }
    # 同一模型选择对照必须复用已闭50的真实产品字节，不能捎带当前target里的后续修复。
    $nativeSnapshotRoot = Join-Path $repoRoot ('tmp/natural-useful-delivery-2026-10-28/' + $nativeBaselineStage)
    $nativeEvidenceRoot = Join-Path $PSScriptRoot $nativeBaselineStage
    $nativeClosure = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $nativeEvidenceRoot 'closure.json') | ConvertFrom-Json
    if ($nativeClosure.status -ne 'CLOSED' -or -not $nativeClosure.entryLockedByClosure) { throw 'Closed immutable Flash50 runtime is required.' }
    $nativeClassesPrefix = [IO.Path]::GetFullPath((Join-Path $nativeSnapshotRoot 'runtime/classes')) + [IO.Path]::DirectorySeparatorChar
    $nativeManifest = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $nativeEvidenceRoot 'source-manifest.json') | ConvertFrom-Json
    $nativeFileCount = 0
    foreach ($entry in $nativeManifest.files) {
        if ([IO.Path]::GetFullPath($entry.path).StartsWith($nativeClassesPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            if ((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw 'Closed Flash50 product runtime drifted.' }
            $nativeFileCount += 1
        }
    }
    if ($nativeFileCount -lt 1) { throw 'The closed Flash50 runtime has no frozen classes.' }
}
if (-not $SkipCompile -and -not $nativeSnapshotRoot) {
    & mvn -o -q -DskipTests compile -f (Join-Path $repoRoot 'services/api/pom.xml')
    if ($LASTEXITCODE -ne 0) { throw 'Backend compile failed.' }
}
$runtimeClasses = Join-Path $evaluationRoot 'runtime/classes'
$runnerClasses = Join-Path $evaluationRoot 'runner'
if (Test-Path -LiteralPath $evaluationRoot) { throw 'Runtime directory already exists; retain evidence and choose a fresh frozen stage.' }
New-Item -ItemType Directory -Path $runtimeClasses,$runnerClasses,(Join-Path $evaluationRoot 'traces') -Force | Out-Null
if ($nativeSnapshotRoot) { Copy-Item -Path (Join-Path $nativeSnapshotRoot 'runtime/classes/*') -Destination $runtimeClasses -Recurse -Force }
else { Copy-Item -Path (Join-Path $repoRoot 'services/api/target/classes/*') -Destination $runtimeClasses -Recurse -Force }
$dependencyPath = Join-Path $evaluationRoot 'dependency-classpath.txt'
if ($nativeSnapshotRoot) { Copy-Item -LiteralPath (Join-Path $nativeSnapshotRoot 'dependency-classpath.txt') -Destination $dependencyPath }
else {
    & mvn -o -q dependency:build-classpath ('-Dmdep.outputFile=' + $dependencyPath) -f (Join-Path $repoRoot 'services/api/pom.xml')
    if ($LASTEXITCODE -ne 0) { throw 'Offline dependency classpath failed.' }
}
$classpath = $runtimeClasses + ';' + [IO.File]::ReadAllText($dependencyPath).Trim()
if ($protocol.versions -contains '52') {
    # 52只在实际题答非空的准备阶段分流；必须快照包含本候选类，禁止拿旧51运行时冒充52。
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeClasses 'com/promptoptimizer/provider/domain/NaturalPromptEffectiveRegistryInstructions.class'))) { throw 'Candidate52 compiled instruction class is missing.' }
}
if ($protocol.versions -contains '54') {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeClasses 'com/promptoptimizer/provider/domain/NaturalPromptSinglePassInstructions.class'))) { throw 'Candidate54 compiled single-pass instruction class is missing.' }
}
if ($protocol.versions -contains '57') {
    if (-not (Test-Path -LiteralPath (Join-Path $runtimeClasses 'com/promptoptimizer/provider/infrastructure/openai/NaturalPromptMetadataRepair.class'))) { throw 'Candidate57 compiled metadata repair class is missing.' }
}
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 21 -cp $classpath -d $runnerClasses (Join-Path $PSScriptRoot 'NaturalUsefulDeliveryEvaluationServer.java') (Join-Path $PSScriptRoot 'NaturalRegistry49CallGuard.java')
if ($LASTEXITCODE -ne 0) { throw 'Evaluation server compile failed.' }
# 配置值只进入临时子进程环境，不打印、不放进命令参数或评测原件。
$names = @('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
$configuration = [IO.File]::ReadAllText($(if ($nativeSnapshotRoot) { Join-Path $runtimeClasses 'application.yml' } else { Join-Path $repoRoot 'services/api/src/main/resources/application.yml' }))
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
if ($protocol.experimentProfile) {
    if ($protocol.experimentProfile -ne 'WRITER_THINKING_ABLATION_V1' -or $protocol.writerThinkingProfile.readTimeoutSeconds -ne 300 -or $protocol.writerThinkingProfile.runnerTimeoutMs -ne 360000) { throw 'Unknown or changed future experiment profile.' }
    # 只给本次隔离writer重放留足读取时间；日常服务、旧阶段和下游参数均不受影响。
    $env:MODEL_READ_TIMEOUT = '300s'
}
$env:MAP_REDUCE_SUMMARY_ENABLED = 'false'
$env:SEMANTIC_RETRIEVAL_ENABLED = 'false'
$env:EMAIL_REGISTRATION_ENABLED = 'false'
$env:SMS_ENABLED = 'false'
$files = @()
foreach ($file in (Get-ChildItem -LiteralPath $runtimeClasses,$runnerClasses -Recurse -File)) {
    $files += [pscustomobject]@{ path = $file.FullName; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$frozen = @((Join-Path $PSScriptRoot 'task-protocol.json'), (Join-Path $stageEvidence 'protocol.json'), (Join-Path $stageEvidence 'cases.json'), (Join-Path $stageEvidence 'jobs.json'), (Join-Path $PSScriptRoot 'NaturalUsefulDeliveryEvaluationServer.java'), (Join-Path $PSScriptRoot 'freeze-live.mjs'), (Join-Path $PSScriptRoot 'run-live.mjs'), (Join-Path $PSScriptRoot 'start-live.ps1'))
$frozen += Join-Path $PSScriptRoot 'writer-thinking-profile.mjs'
$frozen += Join-Path $PSScriptRoot 'official-model-selection-profile.mjs'
if ($protocol.modelSelectionProfile.name -eq 'NATIVE_PRO52_PREPARE_MECHANISM_V1') {
    $frozen += Join-Path $PSScriptRoot 'native-pro52-profile.mjs'
    $frozen += Join-Path $PSScriptRoot 'native-pro52-preparation/verify-model-selection.mjs'
    $frozen += Join-Path $nativeEvidenceRoot 'jobs.json'
    $frozen += Join-Path $nativeEvidenceRoot 'protocol.json'
    $frozen += Join-Path $nativeEvidenceRoot 'traces/001.json'
    $frozen += Join-Path $nativeEvidenceRoot 'traces/003.json'
}
if ($nativeSnapshotRoot) {
    $frozen += Join-Path $nativeEvidenceRoot 'source-manifest.json'
    $frozen += Join-Path $nativeEvidenceRoot 'closure.json'
    $frozen += Join-Path $nativeSnapshotRoot 'dependency-classpath.txt'
    $frozen += Join-Path $PSScriptRoot 'model-selection50-preparation/verify-model-selection.mjs'
}
$frozen += Join-Path $PSScriptRoot 'NaturalRegistry49CallGuard.java'
foreach ($source in @($protocol.writerThinkingProfile.writerSources)) {
    if ($source) {
        $sourcePath = [IO.Path]::GetFullPath((Join-Path $stageEvidence $source.snapshot))
        $sourceRoot = [IO.Path]::GetFullPath((Join-Path $stageEvidence 'writer-sources')) + [IO.Path]::DirectorySeparatorChar
        if (-not $sourcePath.StartsWith($sourceRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Writer source snapshot escaped its frozen directory.' }
        if ((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $source.sha256) { throw 'Frozen writer source snapshot changed.' }
        $frozen += $sourcePath
    }
}
foreach ($file in $frozen) { $files += [pscustomobject]@{ path = $file; sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() } }
if (Test-Path -LiteralPath (Join-Path $stageEvidence 'source-manifest.json')) { throw 'Stage manifest already frozen.' }
[IO.File]::WriteAllText((Join-Path $stageEvidence 'source-manifest.json'), (@{ gitHead = (& git rev-parse HEAD); files = $files } | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$arguments = @('-XX:ActiveProcessorCount=2','-Djava.net.preferIPv4Stack=true',('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $repoRoot 'tmp')),('-Dnatural.eval.accountFile=' + (Join-Path $repoRoot 'tmp/local-acceptance/account.json')),('-Dnatural.eval.root=' + $evaluationRoot),('-Dnatural.eval.evidence=' + $stageEvidence),'-cp',($runnerClasses + ';' + $classpath),'NaturalUsefulDeliveryEvaluationServer')
$process = Start-Process -FilePath (Join-Path $jdk 'java.exe') -ArgumentList $arguments -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationRoot 'server.out.log') -RedirectStandardError (Join-Path $evaluationRoot 'server.err.log')
[IO.File]::WriteAllText((Join-Path $evaluationRoot 'process.json'), (@{pid=$process.Id;port=9014;process='NaturalUsefulDeliveryEvaluationServer';stage=$Stage;workspace=$repoRoot;startedAt=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output ('Started isolated evaluation process pid=' + $process.Id + '; port=9014; no model job has been submitted.')
