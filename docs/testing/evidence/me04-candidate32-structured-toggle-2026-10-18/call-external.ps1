param(
    [Parameter(Mandatory=$true)][ValidateSet('work','review')][string]$Mode,
    [ValidateSet('MAIL','TRANSLATION')][string]$CaseId,
    [Parameter(Mandatory=$true)][ValidateSet('RAW','ENHANCED','R1','R2')][string]$Arm
)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'E:/MyProject/prompt-optimizer-platform'
if($Mode -eq 'work' -and ([string]::IsNullOrWhiteSpace($CaseId) -or $Arm -notin @('RAW','ENHANCED'))){throw 'Frozen work case and arm required'}
if($Mode -eq 'review' -and (-not [string]::IsNullOrWhiteSpace($CaseId) -or $Arm -notin @('R1','R2'))){throw 'Frozen review identifier required'}
# Credentials stay in this server-side process environment; never print values or pass them as arguments.
foreach($evaluationName in @('MODEL_DEEPSEEK_API_KEY','MODEL_API_KEY','MODEL_DEEPSEEK_ENDPOINT')) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
$evaluationScript='docs/testing/evidence/me04-candidate32-structured-toggle-2026-10-18/external-calls.mjs'
if($Mode -eq 'work'){& node.exe $evaluationScript work $CaseId $Arm}
else{& node.exe $evaluationScript review $Arm}
exit $LASTEXITCODE
