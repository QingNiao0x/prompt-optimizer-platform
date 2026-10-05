param([ValidateSet('baseline','candidate','plan','execution','execution-calibrated')][string]$Mode)
$ErrorActionPreference = 'Stop'
# Only explicitly authorized model environment names; values are never written or printed.
foreach ($modelName in @('MODEL_API_KEY','MODEL_API_KEY2','MODEL_DEEPSEEK_API_KEY','MODEL_TOKENHUB_API_KEY')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($modelName))) {
        $userModelValue = [Environment]::GetEnvironmentVariable($modelName, 'User')
        if (-not [string]::IsNullOrWhiteSpace($userModelValue)) { [Environment]::SetEnvironmentVariable($modelName,$userModelValue,'Process') }
    }
}
$tutorialDependencies = (Get-Content -LiteralPath 'tmp/backend-local-managed/2026-10-04T23-34-50-841Z-6be40bad/runtime-classpath.txt' -Raw).Trim()
$tutorialClasspath = (Resolve-Path 'services/api/target/classes').Path + ';' + $tutorialDependencies
& javac -encoding UTF-8 -cp $tutorialClasspath 'tmp/template-tutorial-20261005/TutorialRealEvaluation.java'
if ($LASTEXITCODE -ne 0) { throw 'EVALUATION_COMPILE_FAILED' }
$baselineOverride = if ($Mode -eq 'baseline') { 'tmp/template-tutorial-20261005/baseline-classes;' } else { '' }
& java '-Djdk.net.unixdomain.tmpdir=E:\MyProject\prompt-optimizer-platform\tmp\nio' -cp ('tmp/template-tutorial-20261005;' + $baselineOverride + $tutorialClasspath) TutorialRealEvaluation $Mode
if ($LASTEXITCODE -ne 0) { throw 'EVALUATION_FAILED' }
