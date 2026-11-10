[CmdletBinding()]
param([ValidateSet('inspect','pilot','expand')][string]$Phase = 'inspect', [string]$EvidenceDirectory = 'docs/testing/evidence/text-polish-direct-2026-11-11')
$ErrorActionPreference = 'Stop'
$polishRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
foreach ($polishName in @('MODEL_API_KEY','MODEL_API_KEY2','MODEL_TOKENHUB_API_KEY','MODEL_DEEPSEEK_API_KEY','API_KEY_ENCRYPTION_SECRET','DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_URL')) {
    $polishValue = [Environment]::GetEnvironmentVariable($polishName, 'User')
    if (-not [string]::IsNullOrWhiteSpace($polishValue)) { [Environment]::SetEnvironmentVariable($polishName, $polishValue, 'Process') }
}
$polishEnv = Join-Path $polishRepo '.env.local'
if (Test-Path -LiteralPath $polishEnv) {
    foreach ($polishLine in [IO.File]::ReadAllLines($polishEnv)) {
        $polishParts = $polishLine -split '=',2
        if ($polishParts.Count -eq 2 -and -not $polishParts[0].Trim().StartsWith('#')) {
            [Environment]::SetEnvironmentVariable($polishParts[0].Trim(), $polishParts[1].Trim().Trim([char]34).Trim([char]39), 'Process')
        }
    }
}


Set-Location -LiteralPath $polishRepo
$classes = Join-Path $polishRepo 'tmp/text-polish-direct-classes'
[void][IO.Directory]::CreateDirectory($classes)
$cp = (Join-Path $polishRepo 'services/api/target/classes') + ';' + [IO.File]::ReadAllText((Join-Path $polishRepo 'tmp/image-prompt-runtime-classpath.txt')).Trim()
& E:\Delelop\Java\jdk-21\bin\javac.exe -encoding UTF-8 -cp $cp -d $classes scripts/evaluation/text-polish-direct/DirectComparison.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& E:\Delelop\Java\jdk-21\bin\java.exe -cp ($classes+';'+$cp) com.promptoptimizer.textpolish.service.DirectComparison $EvidenceDirectory $Phase *> (Join-Path $classes ('runtime-' + $Phase + '.log'))
exit $LASTEXITCODE
