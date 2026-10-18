param([ValidateSet('prepare','work')][string]$Operation,[ValidateSet('PURE-TRANSLATION','STRICT-JSON','')][string]$Job='')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
foreach($evaluationName in @('MODEL_API_KEY','MODEL_DEEPSEEK_API_KEY','MODEL_DEEPSEEK_ENDPOINT')){
  $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
  if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
& node (Join-Path $PSScriptRoot 'external-calls-r3.mjs') $Operation $Job
exit $LASTEXITCODE
