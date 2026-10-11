param(
    [ValidateSet('Validate','Plans','Collect','Execute','ExecuteEnhanced')][string]$Action='Validate',
    [string]$InputPath='tmp/p1-task-view-20261011/cases-unanswered-flash.json',
    [string]$RunDirectory='tmp/p1-task-view-20261011/plans-flash',
    [string]$ClassesPath='tmp/p1-task-view-20261011/baseline/target/classes'
)
$ErrorActionPreference='Stop'
$taskRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskClasses=(Resolve-Path -LiteralPath (Join-Path $taskRepo $ClassesPath)).ProviderPath
$taskRun=[IO.Path]::GetFullPath((Join-Path $taskRepo $RunDirectory))
if(-not $taskRun.StartsWith($PSScriptRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Output must stay within this evaluation.'}
$taskCp=$taskClasses+';'+(Get-Content -LiteralPath (Join-Path $taskRepo 'tmp/backend-local-managed/2026-10-08T11-36-35-858Z-31d87dcc/runtime-classpath.txt') -Raw).Trim()
# 每个采集/执行进程单独编译工具，避免两个模型评测覆盖同一份 class 文件。
$taskHarnessClasses=Join-Path $PSScriptRoot ('runner-builds/'+(Split-Path -Leaf $taskRun)+'-'+$Action)
# Only configured DeepSeek credentials enter this child process; values are never printed or saved.
$taskNames=@('MODEL_API_KEY','MODEL_DEEPSEEK_API_KEY','MODEL_DEEPSEEK_ENDPOINT','MODEL_DEEPSEEK_MODELS','MODEL_DEEPSEEK_NAME','MODEL_DEEPSEEK_PROVIDER_NAME','MODEL_MULTI_PROVIDER_ENABLED','MODEL_DEFAULT_PROVIDER')
foreach($taskName in $taskNames){$taskValue=[Environment]::GetEnvironmentVariable($taskName,'User');if(-not [string]::IsNullOrWhiteSpace($taskValue)){[Environment]::SetEnvironmentVariable($taskName,$taskValue,'Process')}}
Push-Location -LiteralPath $taskRepo
try {
    & 'E:\Delelop\Java\jdk-21\bin\javac.exe' -encoding UTF-8 --release 21 -cp $taskCp -d $taskHarnessClasses tmp/p1-task-view-20261011/BackgroundDeliveryRealEvaluation.java
    if($LASTEXITCODE -ne 0){throw 'Harness compilation failed.'}
    & 'E:\Delelop\Java\jdk-21\bin\java.exe' '-XX:ActiveProcessorCount=2' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp ($taskHarnessClasses+';'+$taskCp) com.promptoptimizer.enhancement.service.impl.BackgroundDeliveryRealEvaluation $Action $InputPath $taskRun $taskClasses
    if($LASTEXITCODE -ne 0){throw 'Evaluation stopped; preserved safe failure evidence.'}
} finally {Pop-Location}
