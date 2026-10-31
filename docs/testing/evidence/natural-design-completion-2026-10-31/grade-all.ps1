$ErrorActionPreference='Stop'
$jobs=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'grade-jobs.json') | ConvertFrom-Json
foreach($job in $jobs) {
  & (Join-Path $PSScriptRoot 'run-v2.ps1') -Mode grade -CaseId $job.caseId -Version $job.version -Round $job.round -Order $job.order
  if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
}
