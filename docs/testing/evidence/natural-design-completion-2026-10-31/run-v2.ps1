[CmdletBinding()]
param([ValidateSet('freeze','enhance','plan','execute','grade','count')][string]$Mode,[string]$CaseId,[string]$Version='core-design-completion-v2',[string]$Round='1',[string]$Order='forward')
$ErrorActionPreference='Stop'
$previousEvaluationKey=$env:MODEL_API_KEY
try {
  if (-not $env:MODEL_API_KEY) { $env:MODEL_API_KEY=[Environment]::GetEnvironmentVariable('MODEL_API_KEY','User') }
  & node (Join-Path $PSScriptRoot 'run-v2.mjs') $Mode $CaseId $Version $Round $Order
  exit $LASTEXITCODE
} finally { $env:MODEL_API_KEY=$previousEvaluationKey }
