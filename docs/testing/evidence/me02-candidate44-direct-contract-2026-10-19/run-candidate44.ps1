param([ValidateSet('Prepare','Backend','Serve','Frontend','Browser','Flow','External')][string]$Component='Prepare',[string]$Operation,[string]$Case,[string]$Format='COMPACT')
$ErrorActionPreference='Stop'
$evaluationRepo='E:/MyProject/prompt-optimizer-platform'
Set-Location -LiteralPath $evaluationRepo
$evaluationPublic='E:/MyProject/prompt-optimizer-platform/docs/testing/evidence/me02-candidate44-direct-contract-2026-10-19'
$evaluationPrivate=Join-Path $evaluationRepo 'tmp/me02-candidate44-20261019'
$evaluationRunner=Join-Path $evaluationPrivate 'runner'
$evaluationManifest=Get-Content -LiteralPath (Join-Path $evaluationPublic 'candidate44-source.json') -Raw | ConvertFrom-Json
foreach($evaluationFile in $evaluationManifest.sourceFiles){if((Get-FileHash -LiteralPath $evaluationFile.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen source drift'}}
$evaluationClasses=Join-Path $evaluationRepo $evaluationManifest.runtimeSnapshot
foreach($evaluationFile in $evaluationManifest.runtimeFiles){if((Get-FileHash -LiteralPath (Join-Path $evaluationClasses $evaluationFile.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen runtime drift'}}
if($Component -eq 'Prepare'){
  $evaluationCpFile=Get-ChildItem -LiteralPath 'tmp/backend-local-managed' -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $evaluationDependencies=[IO.File]::ReadAllText($evaluationCpFile.FullName).Trim()
  foreach($entry in $evaluationDependencies.Split(';')){if(-not $entry.EndsWith('.jar',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected dependency entry'}}
  $evaluationCp=$evaluationClasses+';'+$evaluationDependencies
  & 'E:/Delelop/Java/jdk-21/bin/javac.exe' -encoding UTF-8 -cp $evaluationCp -d $evaluationRunner (Join-Path $evaluationRunner 'Candidate44EvaluationServer.java') (Join-Path $evaluationRunner 'ReserveEvaluationCall44.java') (Join-Path $evaluationRunner 'ScopePreflight44.java')
  if($LASTEXITCODE -ne 0){throw 'Harness compilation failed'}
  [IO.File]::WriteAllText((Join-Path $evaluationRunner 'classpath.txt'),$evaluationRunner+';'+$evaluationCp)
  & 'E:/Delelop/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp ($evaluationRunner+';'+$evaluationCp) ScopePreflight44 (Join-Path $evaluationPublic 'request-cases.json') (Join-Path $evaluationPublic 'scope-preflight.json')
  if($LASTEXITCODE -ne 0){throw 'Scope protection failed'}
  exit 0
}
$evaluationProtocol=Get-Content -LiteralPath 'tmp/me00a-20261012/protocol.json' -Raw | ConvertFrom-Json
if($evaluationProtocol.activeBatch.version -ne 'candidate44-direct-contract' -or $evaluationProtocol.activeBatch.status -ne 'OPEN' -or $evaluationProtocol.activeBatch.maximumNewCalls -ne 5 -or $evaluationProtocol.maximumActualModelCalls -ne 500){throw 'Frozen approved batch required'}
if($Component -eq 'Flow'){
  if($Operation -notin @('plan','finish','direct','regenerate') -or $Case -notin @('AI-USAGE','EXPLICIT-OUTPUT') -or $Format -notin @('COMPACT')){throw 'Unknown flow'}
  & node (Join-Path $evaluationRunner 'run-flow.mjs') $Operation $Case $Format
  exit $LASTEXITCODE
}
if($Component -eq 'External'){
  if($Operation -notin @('prepare-works','work')){throw 'Unknown external operation'}
  if($Operation -eq 'work' -and $Case -notin @('COMPACT')){throw 'Unknown work'}
  if($Operation -eq 'review' -and $Case -notin @('R1','R2')){throw 'Unknown review'}
  foreach($evaluationName in @('MODEL_API_KEY','MODEL_DEEPSEEK_API_KEY','MODEL_DEEPSEEK_ENDPOINT')){$value=[Environment]::GetEnvironmentVariable($evaluationName,'User');if(-not [string]::IsNullOrWhiteSpace($value)){[Environment]::SetEnvironmentVariable($evaluationName,$value,'Process')}}
  & node (Join-Path $evaluationRunner 'external-calls.mjs') $Operation $Case
  exit $LASTEXITCODE
}
if($Component -eq 'Serve'){
  $names=@('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
  Get-ChildItem -LiteralPath services/api/src/main/resources -Filter 'application*.yml' | ForEach-Object {foreach($item in [regex]::Matches([IO.File]::ReadAllText($_.FullName),'\$\{([A-Z][A-Z0-9_]+)')){$names+=$item.Groups[1].Value}}
  foreach($name in ($names|Select-Object -Unique)){$value=[Environment]::GetEnvironmentVariable($name,'User');if(-not [string]::IsNullOrWhiteSpace($value)){[Environment]::SetEnvironmentVariable($name,$value,'Process')}}
  & 'E:/Delelop/Java/jdk-21/bin/java.exe' '-XX:ActiveProcessorCount=2' '-Dfile.encoding=UTF-8' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp ([IO.File]::ReadAllText((Join-Path $evaluationRunner 'classpath.txt'))) Candidate44EvaluationServer
  exit $LASTEXITCODE
}
$port=@{Backend=9001;Frontend=5174;Browser=9325}[$Component]
if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw 'Evaluation port occupied; no process stopped'}
if($Component -eq 'Backend'){$process=Start-Process pwsh.exe -ArgumentList @('-NoProfile','-File',(Join-Path $evaluationPublic 'run-candidate44.ps1'),'-Component','Serve') -WorkingDirectory $evaluationRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'backend.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'backend.stderr.log')}
elseif($Component -eq 'Frontend'){$env:VITE_API_PROXY_TARGET='http://127.0.0.1:9001';$process=Start-Process node.exe -ArgumentList @((Join-Path $evaluationRepo 'apps/web/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','5174','--strictPort') -WorkingDirectory (Join-Path $evaluationRepo 'apps/web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'frontend.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'frontend.stderr.log')}
else{$process=Start-Process node.exe -ArgumentList @((Join-Path $evaluationRunner 'account-browser.mjs')) -WorkingDirectory $evaluationRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'browser.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'browser.stderr.log')}
@{component=$Component;pid=$process.Id;startedAt=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evaluationPrivate ($Component.ToLowerInvariant()+'-process.json')) -Encoding UTF8
Write-Output ($Component+' started pid='+$process.Id)
