param([ValidateSet('Prepare','Backend','Serve','Frontend','Browser')][string]$Component='Prepare')
$ErrorActionPreference='Stop'
$evaluationRepo=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
Set-Location -LiteralPath $evaluationRepo
$evaluationPrivate=Join-Path $evaluationRepo 'tmp/me02-candidate42r2-20261019'
$evaluationManifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'candidate42r2-source.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach($evaluationFile in $evaluationManifest.sourceFiles){
  if((Get-FileHash -LiteralPath $evaluationFile.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen source differs'}
}
$evaluationClasses=Join-Path $evaluationRepo $evaluationManifest.runtimeSnapshot
foreach($evaluationFile in $evaluationManifest.runtimeFiles){
  if((Get-FileHash -LiteralPath (Join-Path $evaluationClasses $evaluationFile.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen runtime differs'}
}
New-Item -ItemType Directory -Path (Join-Path $evaluationPrivate 'runner') -Force | Out-Null
$evaluationCpFile=Get-ChildItem -LiteralPath 'tmp/backend-local-managed' -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$evaluationDependencies=[IO.File]::ReadAllText($evaluationCpFile.FullName).Trim()
foreach($evaluationEntry in $evaluationDependencies.Split(';')){if(-not $evaluationEntry.EndsWith('.jar',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected dependency entry'}}
$evaluationRunner=Join-Path $evaluationPrivate 'runner'
$evaluationCp=$evaluationClasses+';'+$evaluationDependencies
if($Component -eq 'Prepare'){
  & 'E:/Delelop/Java/jdk-21/bin/javac.exe' -encoding UTF-8 -cp $evaluationCp -d $evaluationRunner (Join-Path $PSScriptRoot 'Candidate42r2EvaluationServer.java') (Join-Path $PSScriptRoot 'ReserveEvaluationCallR2.java') (Join-Path $PSScriptRoot 'ScopePreflightR2.java')
  if($LASTEXITCODE -ne 0){throw 'Evaluation harness compilation failed'}
  [IO.File]::WriteAllText((Join-Path $evaluationRunner 'classpath.txt'),$evaluationRunner+';'+$evaluationCp)
  & 'E:/Delelop/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp ($evaluationRunner+';'+$evaluationCp) ScopePreflightR2 (Join-Path $PSScriptRoot 'request-cases.json') (Join-Path $PSScriptRoot 'scope-preflight-r2.json')
  if($LASTEXITCODE -ne 0){throw 'Evaluation scope protection failed'}
  exit 0
}
$evaluationProtocol=Get-Content -LiteralPath 'tmp/me00a-20261012/protocol.json' -Raw -Encoding UTF8 | ConvertFrom-Json
if($evaluationProtocol.activeBatch.version -ne 'candidate42r2-source-ownership' -or $evaluationProtocol.activeBatch.status -ne 'OPEN' -or $evaluationProtocol.activeBatch.maximumNewCalls -ne 36 -or $evaluationProtocol.maximumActualModelCalls -ne 500){throw 'Frozen authorization required'}
if($Component -eq 'Serve'){
  $evaluationNames=@('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
  Get-ChildItem -LiteralPath services/api/src/main/resources -Filter 'application*.yml' | ForEach-Object {
    foreach($evaluationMatch in [regex]::Matches([IO.File]::ReadAllText($_.FullName),'\$\{([A-Z][A-Z0-9_]+)')){$evaluationNames+=$evaluationMatch.Groups[1].Value}
  }
  foreach($evaluationName in ($evaluationNames | Select-Object -Unique)){
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
  }
  & 'E:/Delelop/Java/jdk-21/bin/java.exe' '-XX:ActiveProcessorCount=2' '-Dfile.encoding=UTF-8' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp ([IO.File]::ReadAllText((Join-Path $evaluationRunner 'classpath.txt'))) Candidate42r2EvaluationServer
  exit $LASTEXITCODE
}
$evaluationPort=@{Backend=9001;Frontend=5174;Browser=9325}[$Component]
if(Get-NetTCPConnection -State Listen -LocalPort $evaluationPort -ErrorAction SilentlyContinue){throw 'Evaluation port occupied; no process stopped'}
if($Component -eq 'Backend'){
  $evaluationProcess=Start-Process pwsh.exe -ArgumentList @('-NoProfile','-File',(Join-Path $PSScriptRoot 'start-live-r2.ps1'),'-Component','Serve') -WorkingDirectory $evaluationRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'backend.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'backend.stderr.log')
}elseif($Component -eq 'Frontend'){
  $env:VITE_API_PROXY_TARGET='http://127.0.0.1:9001'
  $evaluationProcess=Start-Process node.exe -ArgumentList @((Join-Path $evaluationRepo 'apps/web/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','5174','--strictPort') -WorkingDirectory (Join-Path $evaluationRepo 'apps/web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'frontend.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'frontend.stderr.log')
}else{
  $evaluationProcess=Start-Process node.exe -ArgumentList @((Join-Path $PSScriptRoot 'account-browser.mjs')) -WorkingDirectory $evaluationRepo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evaluationPrivate 'browser.stdout.log') -RedirectStandardError (Join-Path $evaluationPrivate 'browser.stderr.log')
}
@{component=$Component;pid=$evaluationProcess.Id;startedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evaluationPrivate ($Component.ToLowerInvariant()+'-process.json')) -Encoding UTF8
Write-Output ($Component+' started pid='+$evaluationProcess.Id)
