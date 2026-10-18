param([switch]$Serve)
$ErrorActionPreference='Stop'
$evaluationRepo=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
Set-Location -LiteralPath $evaluationRepo
$evaluationManifestPath='docs/testing/evidence/me01-candidate41r2-responsibility-projection-2026-10-18/candidate41r2-source.json'
if((Get-FileHash -LiteralPath $evaluationManifestPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'ab553dacf43189ca49d3b63668a208e8c1d2411abbfa4f73d2a787a65aadd3bd'){throw 'Source manifest changed'}
$evaluationManifest=Get-Content -LiteralPath $evaluationManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$evaluationClasses=Join-Path $evaluationRepo $evaluationManifest.runtimeSnapshot
foreach($evaluationFile in $evaluationManifest.sourceFiles){
    if((Get-FileHash -LiteralPath $evaluationFile.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Current source differs from frozen candidate'}
}
foreach($evaluationFile in $evaluationManifest.runtimeFiles){
    if((Get-FileHash -LiteralPath (Join-Path $evaluationClasses $evaluationFile.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen runtime hash mismatch'}
}
$env:PYTHONUTF8='1'
$env:PYTHONIOENCODING='utf-8'
& python (Join-Path $PSScriptRoot 'prepare-server.py')
if($LASTEXITCODE -ne 0){throw 'Runner preparation failed'}
$evaluationCpFile=Get-ChildItem -LiteralPath 'tmp/backend-local-managed' -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$evaluationDependencies=[IO.File]::ReadAllText($evaluationCpFile.FullName).Trim()
foreach($evaluationEntry in $evaluationDependencies.Split(';')){
    if(-not $evaluationEntry.EndsWith('.jar',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected mutable dependency entry'}
}
$evaluationPrivate=Join-Path $evaluationRepo 'tmp/me01-plan-core-acceptance-20261019'
$evaluationRunner=Join-Path $evaluationPrivate 'runner'
New-Item -ItemType Directory -Path $evaluationRunner -Force | Out-Null
$evaluationCp=$evaluationClasses+';'+$evaluationDependencies
$evaluationSources=@('PlanCoreAcceptanceServer.java','PlanCoreScopePreflight.java') | ForEach-Object {Join-Path (Join-Path $evaluationPrivate 'harness') $_}
& 'E:/Delelop/Java/jdk-21/bin/javac.exe' '-encoding' 'UTF-8' '-cp' $evaluationCp '-d' $evaluationRunner @evaluationSources
if($LASTEXITCODE -ne 0){throw 'Harness compilation failed'}
$evaluationCp=$evaluationRunner+';'+$evaluationCp
[IO.File]::WriteAllText((Join-Path $evaluationRunner 'classpath.txt'),$evaluationCp)
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp $evaluationCp PlanCoreScopePreflight (Join-Path $PSScriptRoot 'request-cases.json') (Join-Path $PSScriptRoot 'scope-preflight.json')
if($LASTEXITCODE -ne 0){throw 'Offline scope checks failed'}
if(-not $Serve){exit 0}
# 开始真实请求前仍由服务器核对正式批准及逐调用预算；本脚本不激活批次。
if(Get-NetTCPConnection -State Listen -LocalPort 9001 -ErrorAction SilentlyContinue){throw 'Port 9001 occupied; no process stopped'}
$evaluationNames=@('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
Get-ChildItem -LiteralPath 'services/api/src/main/resources' -Filter 'application*.yml' | ForEach-Object {
    foreach($evaluationMatch in [regex]::Matches([IO.File]::ReadAllText($_.FullName),'\$\{([A-Z][A-Z0-9_]+)')){$evaluationNames+=$evaluationMatch.Groups[1].Value}
}
foreach($evaluationName in ($evaluationNames | Select-Object -Unique)){
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-XX:ActiveProcessorCount=2' '-Dfile.encoding=UTF-8' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp $evaluationCp PlanCoreAcceptanceServer
exit $LASTEXITCODE
