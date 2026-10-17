param([ValidateSet('Targeted','Affected')][string]$Stage='Affected')
$ErrorActionPreference='Stop'
$evaluationRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
Set-Location -LiteralPath $evaluationRoot
$evaluationWork=Join-Path $evaluationRoot 'tmp/me00a-candidate25-20261017'
New-Item -ItemType Directory -Path $evaluationWork -Force | Out-Null

# 用现有Python XML解析生成隔离POM；本脚本以UTF-8 BOM保存，供Windows PowerShell读取中文注释。
# 不修改api的原pom、正在运行的classes或任何已有冻结快照。
& python (Join-Path $PSScriptRoot 'prepare-build.py')
if($LASTEXITCODE -ne 0){throw 'Isolated build preparation failed'}
$evaluationPomPath=Join-Path $evaluationWork 'pom.xml'
$evaluationSelection=@('CandidateTwentyFiveReplayTest')
if($Stage -eq 'Affected'){
    $evaluationPrevious=Get-Content -LiteralPath 'docs/testing/evidence/me00a-candidate24-offline-2026-10-17/verification-r3.json' -Raw -Encoding UTF8 | ConvertFrom-Json
    $evaluationSelection+=@($evaluationPrevious.java.reports | ForEach-Object {$_.class.Split('.')[-1]})
    # 共享委托／评分工具的直接调用测试不能因上轮仅改TaskConstraintCoverage而漏跑。
    $evaluationSelection+=@(& rg -l 'PlanDecisionIntegrator' 'services/api/src/test/java' | ForEach-Object {[IO.Path]::GetFileNameWithoutExtension($_)})
}
$evaluationSelection=@($evaluationSelection | Sort-Object -Unique)
$evaluationBefore=@(Get-ChildItem -LiteralPath 'tmp/me00a-20261012/traces' -Filter '*.json').Count
$env:JAVA_HOME='E:/Delelop/Java/jdk-21'
$evaluationArgs=@('-o','-f',$evaluationPomPath,('-Dtest='+($evaluationSelection -join ',')),
    '-Dmaven.compiler.fork=true','-Dmaven.compiler.executable=E:/Delelop/Java/jdk-21/bin/javac.exe',
    '-Djvm=E:/Delelop/Java/jdk-21/bin/java.exe',
    '-DargLine=-XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp','test')
$evaluationLog=Join-Path $evaluationWork ($Stage.ToLowerInvariant()+'-build.log')
$evaluationStarted=[DateTimeOffset]::UtcNow.ToString('o')
[IO.File]::WriteAllText((Join-Path $evaluationWork ($Stage.ToLowerInvariant()+'-started.json')),
    (@{startedAt=$evaluationStarted;classes=$evaluationSelection} | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
# Windows PowerShell会把ByteBuddy的普通stderr警告变成ErrorRecord；以最终Maven退出码和XML结果判定。
$evaluationSavedErrorAction=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    & 'E:/Delelop/apache-maven-3.9.14/bin/mvn.cmd' @evaluationArgs *> $evaluationLog
    $evaluationExit=$LASTEXITCODE
} finally {$ErrorActionPreference=$evaluationSavedErrorAction}
if(@(Get-ChildItem -LiteralPath 'tmp/me00a-20261012/traces' -Filter '*.json').Count -ne $evaluationBefore){throw 'Real model ledger changed during offline tests'}
[IO.File]::WriteAllText((Join-Path $evaluationWork ($Stage.ToLowerInvariant()+'-selection.json')),($evaluationSelection | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Get-Content -LiteralPath $evaluationLog -Tail 16 -Encoding UTF8
[pscustomobject]@{stage=$Stage;selectedClasses=$evaluationSelection.Count;mavenExit=$evaluationExit;realModelCalls=0} | ConvertTo-Json -Compress
exit $evaluationExit
