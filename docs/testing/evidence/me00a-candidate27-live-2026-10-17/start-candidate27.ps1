param()
$ErrorActionPreference='Stop'
$evaluationRepo='E:\MyProject\prompt-optimizer-platform'
Set-Location -LiteralPath $evaluationRepo
if(Get-NetTCPConnection -State Listen -LocalPort 9001 -ErrorAction SilentlyContinue){throw 'Port 9001 occupied; no process stopped'}
# Reuse local server environment; never print inherited values or pass secrets as command arguments.
$evaluationNames=@('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
Get-ChildItem -LiteralPath 'services/api/src/main/resources' -Filter 'application*.yml' | ForEach-Object {
    $evaluationText=[IO.File]::ReadAllText($_.FullName)
    foreach($evaluationMatch in [regex]::Matches($evaluationText,'\$\{([A-Z][A-Z0-9_]+)')){$evaluationNames+=$evaluationMatch.Groups[1].Value}
}
foreach($evaluationName in ($evaluationNames | Select-Object -Unique)) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
$evaluationCpFile=Get-ChildItem -LiteralPath 'tmp/backend-local-managed' -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$evaluationDependencies=[IO.File]::ReadAllText($evaluationCpFile.FullName).Trim()
if($evaluationDependencies -match 'target[\\/]classes'){throw 'Mutable classes in dependency list'}
$evaluationClasses=Join-Path $evaluationRepo 'tmp/me00a-candidate27-20261017/frozen/classes'
$evaluationRunner=Join-Path $evaluationRepo 'tmp/me00a-candidate27-live-20261017/runner'
New-Item -ItemType Directory -Path $evaluationRunner -Force | Out-Null
$evaluationCp=$evaluationClasses+';'+$evaluationDependencies
$evaluationSources=@('Candidate27EvaluationServer.java','HarnessScopeTest.java','TaskViewFixture.java') | ForEach-Object {Join-Path 'docs/testing/evidence/me00a-candidate27-live-2026-10-17' $_}
& 'E:/Delelop/Java/jdk-21/bin/javac.exe' '-encoding' 'UTF-8' '-cp' $evaluationCp '-d' $evaluationRunner @evaluationSources
if($LASTEXITCODE -ne 0){throw 'Harness compilation failed'}
$evaluationCp=$evaluationRunner+';'+$evaluationCp
[IO.File]::WriteAllText((Join-Path $evaluationRunner 'classpath.txt'),$evaluationCp)
# Preflight starts local HTTP only; every external request still requires the approved, open batch.
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-XX:ActiveProcessorCount=2' '-Dfile.encoding=UTF-8' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp $evaluationCp Candidate27EvaluationServer candidate27-live --preflight
exit $LASTEXITCODE
