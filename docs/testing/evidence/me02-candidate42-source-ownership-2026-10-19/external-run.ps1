param([ValidateSet('work','review','prepare-works','prepare-reviews')][string]$Operation,[string]$Job)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
if($Operation -eq 'work' -and $Job -notin @('REQUESTS_RAW','REQUESTS_COMPACT','REQUESTS_STRUCTURED','REFUND_RAW','REFUND_PLAN','JSON_STRUCTURED')){throw 'Frozen work required'}
if($Operation -eq 'review' -and $Job -notin @('R1','R2')){throw 'Frozen reviewer required'}
foreach($evaluationName in @('MODEL_API_KEY','MODEL_DEEPSEEK_API_KEY','MODEL_DEEPSEEK_ENDPOINT')){
  $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
  if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
& node (Join-Path $PSScriptRoot 'external-calls-r2.mjs') $Operation $Job
exit $LASTEXITCODE
