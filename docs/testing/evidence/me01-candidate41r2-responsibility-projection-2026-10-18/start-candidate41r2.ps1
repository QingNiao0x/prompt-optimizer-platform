param([switch]$Serve)
$ErrorActionPreference='Stop'
$evaluationRepo='E:\MyProject\prompt-optimizer-platform'
Set-Location -LiteralPath $evaluationRepo
$evaluationEvidence=Join-Path $evaluationRepo 'docs/testing/evidence/me01-candidate41r2-responsibility-projection-2026-10-18'
$evaluationManifest=Get-Content -LiteralPath (Join-Path $evaluationEvidence 'candidate41r2-source.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$evaluationClasses=Join-Path $evaluationRepo $evaluationManifest.runtimeSnapshot
if($evaluationManifest.version -ne 'candidate41r2' -or $evaluationManifest.runtimeSnapshot -notin @('tmp/me01-candidate41r2-20261018/frozen/classes')){throw 'Unexpected frozen runtime'}
# Only the frozen compiled runtime is loaded; current target/build classes never enter this classpath.
foreach($evaluationFile in $evaluationManifest.runtimeFiles){
    $evaluationPath=Join-Path $evaluationClasses $evaluationFile.path
    if((Get-FileHash -LiteralPath $evaluationPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen runtime hash mismatch'}
}
$evaluationCpFile=Get-ChildItem -LiteralPath 'tmp/backend-local-managed' -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$evaluationDependencies=[IO.File]::ReadAllText($evaluationCpFile.FullName).Trim()
foreach($evaluationEntry in $evaluationDependencies.Split(';')){
    if(-not $evaluationEntry.EndsWith('.jar',[StringComparison]::OrdinalIgnoreCase)){throw 'Dependency classpath contains non-jar mutable entry'}
}
$evaluationRunner=Join-Path $evaluationRepo 'tmp/me01-candidate41r2-live-20261018/runner'
New-Item -ItemType Directory -Path $evaluationRunner -Force | Out-Null
$evaluationCp=$evaluationClasses+';'+$evaluationDependencies
$evaluationSources=@('Candidate41EvaluationServer.java','ReserveEvaluationCall.java','Candidate41ScopePreflight.java') | ForEach-Object {Join-Path $evaluationEvidence $_}
& 'E:/Delelop/Java/jdk-21/bin/javac.exe' '-encoding' 'UTF-8' '-cp' $evaluationCp '-d' $evaluationRunner @evaluationSources
if($LASTEXITCODE -ne 0){throw 'Harness compilation failed'}
$evaluationCp=$evaluationRunner+';'+$evaluationCp
[IO.File]::WriteAllText((Join-Path $evaluationRunner 'classpath.txt'),$evaluationCp)
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-Dfile.encoding=UTF-8' -cp $evaluationCp Candidate41ScopePreflight (Join-Path $evaluationEvidence 'request-cases.json') (Join-Path $evaluationEvidence 'scope-preflight.json')
if($LASTEXITCODE -ne 0){throw 'Offline scope preflight failed'}
if(-not $Serve){exit 0}
if(Get-NetTCPConnection -State Listen -LocalPort 9001 -ErrorAction SilentlyContinue){throw 'Port 9001 occupied; no process stopped'}
# Reuse existing local provider environment; values never enter stdout or process arguments.
$evaluationNames=@('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
Get-ChildItem -LiteralPath 'services/api/src/main/resources' -Filter 'application*.yml' | ForEach-Object {
    $evaluationText=[IO.File]::ReadAllText($_.FullName)
    foreach($evaluationMatch in [regex]::Matches($evaluationText,'\$\{([A-Z][A-Z0-9_]+)')){$evaluationNames+=$evaluationMatch.Groups[1].Value}
}
foreach($evaluationName in ($evaluationNames | Select-Object -Unique)) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
# Foreground only. The caller owns any explicit hidden background process and its lifecycle.
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-XX:ActiveProcessorCount=2' '-Dfile.encoding=UTF-8' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp $evaluationCp Candidate41EvaluationServer
exit $LASTEXITCODE
