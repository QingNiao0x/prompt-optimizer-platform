param([ValidateSet('Backend','Frontend','Browser')][string]$Component)
$ErrorActionPreference='Stop'
$evaluationRepo=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
Set-Location -LiteralPath $evaluationRepo
$evaluationProtocol=Get-Content -LiteralPath (Join-Path $evaluationRepo 'tmp/me00a-20261012/protocol.json') -Raw | ConvertFrom-Json
$evaluationBatch=$evaluationProtocol.activeBatch
if($evaluationBatch.version -ne 'candidate41r2-core-acceptance' -or $evaluationBatch.status -ne 'OPEN' -or
   $evaluationBatch.authorizationStatus -ne 'APPROVED_BY_USER' -or $evaluationBatch.callsBefore -ne 434 -or
   $evaluationBatch.maximumNewCalls -ne 18 -or $evaluationProtocol.maximumActualModelCalls -ne 462 -or
   $evaluationProtocol.phaseLimits.baseline -ne 80 -or $evaluationProtocol.phaseLimits.candidateValidation -ne 382){
  throw 'Frozen approved batch required before any evaluation service startup'
}
$evaluationManifest=Join-Path $evaluationRepo 'docs/testing/evidence/me01-candidate41r2-responsibility-projection-2026-10-18/candidate41r2-source.json'
if((Get-FileHash -LiteralPath $evaluationManifest -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationBatch.sourceManifestSha256){
  throw 'Frozen source manifest mismatch'
}
$evaluationPrivate=Join-Path $evaluationRepo 'tmp/me01-plan-core-acceptance-20261019'
New-Item -ItemType Directory -Path $evaluationPrivate -Force | Out-Null
$evaluationPorts=@{Backend=9001;Frontend=5174;Browser=9325}
if(Get-NetTCPConnection -State Listen -LocalPort $evaluationPorts[$Component] -ErrorAction SilentlyContinue){throw 'Evaluation port occupied; no process stopped'}
switch($Component){
  'Backend' {
    $evaluationFile=Join-Path $PSScriptRoot 'start-acceptance.ps1'
    $evaluationProcess=Start-Process (Get-Command pwsh.exe).Source -ArgumentList @('-NoProfile','-File',$evaluationFile,'-Serve') -WorkingDirectory $evaluationRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'backend.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'backend.stderr.log')
  }
  'Frontend' {
    $env:VITE_API_PROXY_TARGET='http://127.0.0.1:9001'
    $evaluationVite=Join-Path $evaluationRepo 'apps/web/node_modules/vite/bin/vite.js'
    $evaluationProcess=Start-Process node.exe -ArgumentList @($evaluationVite,'--host','127.0.0.1','--port','5174','--strictPort') -WorkingDirectory (Join-Path $evaluationRepo 'apps/web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'frontend.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'frontend.stderr.log')
  }
  'Browser' {
    New-Item -ItemType Directory -Path (Join-Path $evaluationPrivate 'live-auth') -Force | Out-Null
    $evaluationProcess=Start-Process node.exe -ArgumentList @((Join-Path $PSScriptRoot 'account-browser.mjs')) -WorkingDirectory $evaluationRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'browser.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'browser.stderr.log')
  }
}
@{component=$Component;pid=$evaluationProcess.Id;startedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evaluationPrivate ($Component.ToLowerInvariant()+'-process.json')) -Encoding UTF8
Write-Output ($Component+' started pid='+$evaluationProcess.Id)
