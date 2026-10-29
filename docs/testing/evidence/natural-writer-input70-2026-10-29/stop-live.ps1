[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string] $Stage)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$taskRuntime = [IO.Path]::GetFullPath((Join-Path $repoRoot 'tmp/natural-writer-input70-2026-10-29'))
$evaluationRoot = [IO.Path]::GetFullPath((Join-Path $taskRuntime $Stage))
if (-not $evaluationRoot.StartsWith($taskRuntime + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime outside this task.' }
$ownership = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $evaluationRoot 'process.json') | ConvertFrom-Json
if ($ownership.port -ne 9014 -or $ownership.process -ne 'NaturalUsefulDeliveryEvaluationServer' -or $ownership.stage -ne $Stage) { throw 'Process metadata mismatch.' }
$ownedProcess = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$ownership.pid) -ErrorAction Stop
if (-not $ownedProcess) { Write-Output ('Owned evaluation process already stopped; pid=' + $ownership.pid); exit 0 }
if (-not $ownedProcess.CommandLine.Contains('NaturalUsefulDeliveryEvaluationServer') -or -not $ownedProcess.CommandLine.Contains($evaluationRoot)) { throw 'Live process identity mismatch; no process stopped.' }
Stop-Process -Id ([int]$ownership.pid) -ErrorAction Stop
Write-Output ('Stopped own evaluation process pid=' + $ownership.pid + '; port=9014; evidence retained.')
