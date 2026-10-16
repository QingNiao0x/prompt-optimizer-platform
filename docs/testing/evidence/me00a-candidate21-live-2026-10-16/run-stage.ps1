param([Parameter(Mandatory=$true)][ValidateSet('preflight','direct','plan','plan-final','direct-parity','copy','execute','review')][string]$Stage)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'E:\MyProject\prompt-optimizer-platform'
# Inherit the same local server environment; keys never appear in command arguments or output.
$evaluationNames=@()
Get-ChildItem -LiteralPath 'services/api/src/main/resources' -Filter 'application*.yml' | ForEach-Object {
    $evaluationText=[IO.File]::ReadAllText($_.FullName)
    foreach($evaluationMatch in [regex]::Matches($evaluationText,'\$\{([A-Z][A-Z0-9_]+)')){$evaluationNames+=$evaluationMatch.Groups[1].Value}
}
foreach($evaluationName in ($evaluationNames | Select-Object -Unique)) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
& 'E:/Delelop/nodejs/node.exe' 'docs/testing/evidence/me00a-candidate21-live-2026-10-16/run.mjs' $Stage
exit $LASTEXITCODE
