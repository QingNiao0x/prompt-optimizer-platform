param([ValidateSet('work','review')][string]$Operation,[string]$Group,[string]$Arm)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Resolve-Path (Join-Path $PSScriptRoot '../../../..'))
# Only the fixed official-provider values are passed privately to the server-side helper.
foreach($evaluationName in @('MODEL_DEEPSEEK_API_KEY','MODEL_API_KEY','MODEL_DEEPSEEK_ENDPOINT')){
  $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
  if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
& node (Join-Path $PSScriptRoot 'external-calls.mjs') $Operation $Group $Arm
exit $LASTEXITCODE
