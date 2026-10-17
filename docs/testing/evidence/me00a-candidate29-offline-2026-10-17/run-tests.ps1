param([ValidateSet('Targeted','Affected')][string]$Stage='Affected')
$ErrorActionPreference='Stop'
$evaluationRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
Set-Location -LiteralPath $evaluationRoot
$evaluationWork=Join-Path $evaluationRoot 'tmp/me00a-candidate29-20261017'
New-Item -ItemType Directory -Path $evaluationWork -Force | Out-Null
# 历史回放测试按Maven工作目录输出派生JSON，先创建目录，避免测试顺序偶然决定能否写证据。
New-Item -ItemType Directory -Path (Join-Path $evaluationWork 'target') -Force | Out-Null

# 用现有Python XML解析生成隔离POM；本脚本以UTF-8 BOM保存，供Windows PowerShell读取中文注释。
# 不修改api的原pom、正在运行的classes或任何已有冻结快照。
& python (Join-Path $PSScriptRoot 'prepare-build.py')
if($LASTEXITCODE -ne 0){throw 'Isolated build preparation failed'}
$evaluationPomPath=Join-Path $evaluationWork 'pom.xml'
$evaluationSelection=@('ModelFindingConsistencyOrderTest','TaskCompletionRegressionTest','PlanQuestionFilterTest','CandidateTwentySevenRepairProtocolTest')
if($Stage -eq 'Affected'){
    $evaluationPrevious=Get-Content -LiteralPath 'docs/testing/evidence/me00a-candidate28-offline-2026-10-17/verification.json' -Raw -Encoding UTF8 | ConvertFrom-Json
    $evaluationSelection+=@($evaluationPrevious.java.reports | ForEach-Object {$_.class.Split('.')[-1]})
    # 分类语义及任务视图是共享路径；直接调用方和上轮组装／安全／历史范围共同回归。
    $evaluationSelection+='OpenAiCompatiblePromptEnhancementProviderTest'
    $evaluationSelection+=@(& rg -l 'RequirementBoundaryContract|prohibitsInventedData' 'services/api/src/test/java' | ForEach-Object {[IO.Path]::GetFileNameWithoutExtension($_)})
    $evaluationSelection+=@(& rg -l 'PlanAnswerSemantics|ConfirmedDecisionSet|ResolvedPlanState|EffectiveTaskViewFactory|PlanDecisionIntegrator' 'services/api/src/test/java' | ForEach-Object {[IO.Path]::GetFileNameWithoutExtension($_)})
}
$evaluationSelection=@($evaluationSelection | Sort-Object -Unique)
$evaluationBefore=@(Get-ChildItem -LiteralPath 'tmp/me00a-20261012/traces' -Filter '*.json').Count
$env:JAVA_HOME='E:/Delelop/Java/jdk-21'
$evaluationArgs=@('-o','-f',$evaluationPomPath,('-Dtest='+($evaluationSelection -join ',')),
    '-Dmaven.compiler.fork=true','-Dmaven.compiler.executable=E:/Delelop/Java/jdk-21/bin/javac.exe',
    '-Djvm=E:/Delelop/Java/jdk-21/bin/java.exe',
    '-DargLine=-javaagent:E:/Delelop/localRepository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp','test')
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
    # 中断的原生进程可能返回0；没有完整构建成功标记时不能记为测试通过。
    if($evaluationExit -eq 0 -and -not (Select-String -LiteralPath $evaluationLog -Pattern '\[INFO\] BUILD SUCCESS' -Quiet)){ $evaluationExit=1 }
} finally {$ErrorActionPreference=$evaluationSavedErrorAction}
if(@(Get-ChildItem -LiteralPath 'tmp/me00a-20261012/traces' -Filter '*.json').Count -ne $evaluationBefore){throw 'Real model ledger changed during offline tests'}
[IO.File]::WriteAllText((Join-Path $evaluationWork ($Stage.ToLowerInvariant()+'-selection.json')),($evaluationSelection | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Get-Content -LiteralPath $evaluationLog -Tail 16 -Encoding UTF8
[pscustomobject]@{stage=$Stage;selectedClasses=$evaluationSelection.Count;mavenExit=$evaluationExit;realModelCalls=0} | ConvertTo-Json -Compress
exit $evaluationExit
