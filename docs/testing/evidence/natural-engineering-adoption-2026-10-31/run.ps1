[CmdletBinding()]
param([ValidateSet('freeze','plan','enhance','execute','count')][string]$Mode,[string]$CaseId,[string]$Version='core-repair-v3',[string]$Repeat='1')
$ErrorActionPreference='Stop'
$previousEvaluationKey=$env:MODEL_API_KEY
try {
    if (-not $env:MODEL_API_KEY) { $env:MODEL_API_KEY=[Environment]::GetEnvironmentVariable('MODEL_API_KEY','User') }
    & node (Join-Path $PSScriptRoot 'run.mjs') $Mode $CaseId $Version $Repeat
    exit $LASTEXITCODE
} finally { $env:MODEL_API_KEY=$previousEvaluationKey }
