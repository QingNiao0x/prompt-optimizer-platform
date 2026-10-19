param([ValidateSet('Prepare','Backend','Serve','Frontend','Browser','Flow','External')][string]$Component='Prepare',[string]$Operation,[string]$Case,[string]$Format='COMPACT')
$ErrorActionPreference='Stop'
$evaluationRepo=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
& (Join-Path $evaluationRepo 'tmp/me02-candidate43r1-20261019/runner/run-live.ps1') -Component $Component -Operation $Operation -Case $Case -Format $Format
exit $LASTEXITCODE
