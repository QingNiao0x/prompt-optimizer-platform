param([ValidateSet('work','review')][string]$Mode,[ValidateSet('RAW','PLAN','R1','R2')][string]$Arm)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'E:/MyProject/prompt-optimizer-platform'
# Credentials remain in this server-side process environment, never in browser or arguments.
foreach($evaluationName in @('MODEL_DEEPSEEK_API_KEY','MODEL_API_KEY','MODEL_DEEPSEEK_ENDPOINT')) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
& node.exe 'docs/testing/evidence/me00a-candidate31-contract-lookup-2026-10-17/external-calls.mjs' $Mode $Arm
exit $LASTEXITCODE
