param([switch]$CheckOnly)
$ErrorActionPreference='Stop'
$evaluationRepo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
Set-Location -LiteralPath $evaluationRepo

# 复用既有本机启动方式：只把本项目声明的用户级环境项传给子进程，不打印值或读取.env。
$evaluationNames=@('ALIBABA_CLOUD_ACCESS_KEY_ID','ALIBABA_CLOUD_ACCESS_KEY_SECRET','ALIBABA_CLOUD_SECURITY_TOKEN')
Get-ChildItem -LiteralPath 'services/api/src/main/resources' -Filter 'application*.yml' | ForEach-Object {
    $evaluationText=[IO.File]::ReadAllText($_.FullName)
    foreach($evaluationMatch in [regex]::Matches($evaluationText,'\$\{([A-Z][A-Z0-9_]+)')) {
        $evaluationNames+=$evaluationMatch.Groups[1].Value
    }
}
foreach($evaluationName in ($evaluationNames | Select-Object -Unique)) {
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if(-not [string]::IsNullOrWhiteSpace($evaluationValue)) {
        [Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')
    }
}

# 管理脚本先检查归属、编译和NIO，任一前置失败都保持旧服务；只接管本项目9000端口。
& (Join-Path $evaluationRepo 'scripts/manage-backend-local.ps1') -Action Restart -CheckOnly:$CheckOnly
exit $LASTEXITCODE
