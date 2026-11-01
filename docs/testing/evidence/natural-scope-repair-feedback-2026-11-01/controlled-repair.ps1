param()
$ErrorActionPreference='Stop'
$previousEvaluationKey=$env:MODEL_API_KEY
try {
  if(-not $env:MODEL_API_KEY){$env:MODEL_API_KEY=[Environment]::GetEnvironmentVariable('MODEL_API_KEY','User')}
  & node (Join-Path $PSScriptRoot 'controlled-repair.mjs')
  exit $LASTEXITCODE
} finally {$env:MODEL_API_KEY=$previousEvaluationKey}
