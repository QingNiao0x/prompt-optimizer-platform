[CmdletBinding()]
param([ValidateSet('freeze','generate','execute','count')][string]$Mode,[string]$CaseId,[string]$Version='core-repair-v2',[string]$Operation='enhance',[string]$ToolRecovery='')
$ErrorActionPreference='Stop'
$previousEvaluationKey=$env:MODEL_API_KEY
try {
    if (-not $env:MODEL_API_KEY) { $env:MODEL_API_KEY=[Environment]::GetEnvironmentVariable('MODEL_API_KEY','User') }
    & node (Join-Path $PSScriptRoot 'run.mjs') $Mode $CaseId $Version $Operation $ToolRecovery
    exit $LASTEXITCODE
} finally { $env:MODEL_API_KEY=$previousEvaluationKey }
