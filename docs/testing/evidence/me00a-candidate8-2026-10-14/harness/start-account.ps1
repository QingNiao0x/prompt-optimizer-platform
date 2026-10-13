$ErrorActionPreference='Stop'
$evaluationRepo=(Get-Location).Path
$evaluationRoot=Join-Path $evaluationRepo 'tmp/me00a-candidate8-20261014'
$evaluationAccount=Join-Path $evaluationRepo 'tmp/enhancement-quality-20261012'
if(Get-NetTCPConnection -State Listen -LocalPort 9325 -ErrorAction SilentlyContinue){throw 'Browser port occupied; no process stopped'}
foreach($evaluationName in @('DB_URL','DB_USERNAME','DB_PASSWORD')) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if($evaluationValue){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}
}
$evaluationCpFile=Get-ChildItem -LiteralPath 'tmp/backend-local-managed' -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$evaluationCp=(Join-Path $evaluationRoot 'candidate8/classes')+';'+[IO.File]::ReadAllText($evaluationCpFile.FullName).Trim()
$env:PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER=([IO.File]::ReadAllText((Join-Path $evaluationAccount 'account.json')) | ConvertFrom-Json).identifier
$env:PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD='Qa!'+[Guid]::NewGuid().ToString('N')+'8aZ'
try {
    & 'E:/Delelop/Java/jdk-21/bin/javac.exe' -encoding UTF-8 --release 21 -cp $evaluationCp -d (Join-Path $evaluationRoot 'account-runner') 'scripts/java/LocalAcceptanceAccountProvisioner.java' (Join-Path $evaluationAccount 'RecoverEvaluationCredential.java')
    if($LASTEXITCODE -ne 0){throw 'Fixture recovery compile failed'}
    # 原工具只更新已存在且 ID、租户均匹配的合成账号密码；不会调用创建账号入口。
    & 'E:/Delelop/Java/jdk-21/bin/java.exe' -cp ((Join-Path $evaluationRoot 'account-runner')+';'+$evaluationCp) RecoverEvaluationCredential
    if($LASTEXITCODE -ne 0){throw 'Existing fixture recovery failed'}
    & node 'docs/testing/evidence/me00a-candidate8-2026-10-14/harness/account-browser.mjs'
    exit $LASTEXITCODE
} finally {
    Remove-Item Env:PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER -ErrorAction SilentlyContinue
}
