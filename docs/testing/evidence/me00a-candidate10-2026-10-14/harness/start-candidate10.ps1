param()
$ErrorActionPreference='Stop'
# 从明确的仓库工作目录启动，避免归档目录层级影响 class 与协议位置。
$evaluationRepo=(Get-Location).Path
if(-not (Test-Path -LiteralPath (Join-Path $evaluationRepo 'AGENTS.md'))){throw 'Run from repository root'}
if(Get-NetTCPConnection -State Listen -LocalPort 9001 -ErrorAction SilentlyContinue){throw 'Port 9001 occupied; no process stopped'}
$evaluationNames=@('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
Get-ChildItem -LiteralPath (Join-Path $evaluationRepo 'services/api/src/main/resources') -Filter 'application*.yml' | ForEach-Object {
    $evaluationText=[IO.File]::ReadAllText($_.FullName)
    foreach($evaluationMatch in [regex]::Matches($evaluationText,'\$\{([A-Z][A-Z0-9_]+)')){$evaluationNames+=$evaluationMatch.Groups[1].Value}
}
foreach($evaluationName in ($evaluationNames | Select-Object -Unique)) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
$evaluationCpFile=Get-ChildItem -LiteralPath (Join-Path $evaluationRepo 'tmp/backend-local-managed') -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$evaluationDependencies=[IO.File]::ReadAllText($evaluationCpFile.FullName).Trim()
if($evaluationDependencies -match 'target[\\/]classes'){throw 'Mutable classes in dependency list'}
$evaluationClasses=Join-Path $evaluationRepo 'tmp/me00a-candidate10-20261014/candidate10/classes'
$evaluationRunner=Join-Path $evaluationRepo 'tmp/me00a-candidate10-20261014/runner'
$evaluationCp=$evaluationRunner+';'+$evaluationClasses+';'+$evaluationDependencies
# 密钥仅经服务端进程环境注入；参数和 stdout 均不携带凭证。
& 'E:/Delelop/Java/jdk-21/bin/java.exe' '-XX:ActiveProcessorCount=2' '-Dfile.encoding=UTF-8' '-Djdk.net.unixdomain.tmpdir=E:/MyProject/prompt-optimizer-platform/tmp' -cp $evaluationCp MeEvaluationServer candidate10
exit $LASTEXITCODE
