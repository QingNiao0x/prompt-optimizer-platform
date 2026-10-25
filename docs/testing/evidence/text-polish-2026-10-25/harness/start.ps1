$ErrorActionPreference = 'Stop'
$polishRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
foreach ($polishName in @('MODEL_API_KEY','MODEL_API_KEY2','MODEL_TOKENHUB_API_KEY','MODEL_DEEPSEEK_API_KEY','API_KEY_ENCRYPTION_SECRET','DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_URL')) {
    $polishValue = [Environment]::GetEnvironmentVariable($polishName, 'User')
    if (-not [string]::IsNullOrWhiteSpace($polishValue)) { [Environment]::SetEnvironmentVariable($polishName, $polishValue, 'Process') }
}
$polishEnv = Join-Path $polishRepo '.env.local'
if (Test-Path -LiteralPath $polishEnv) {
    foreach ($polishLine in [IO.File]::ReadAllLines($polishEnv)) {
        $polishParts = $polishLine -split '=',2
        if ($polishParts.Count -eq 2 -and -not $polishParts[0].Trim().StartsWith('#')) {
            [Environment]::SetEnvironmentVariable($polishParts[0].Trim(), $polishParts[1].Trim().Trim([char]34).Trim([char]39), 'Process')
        }
    }
}
$polishClasspath = (Join-Path $polishRepo 'services\api\target\classes') + ';' + [IO.File]::ReadAllText((Join-Path $polishRepo 'tmp\image-prompt-runtime-classpath.txt')).Trim()
$polishNio = Join-Path $polishRepo 'tmp\t'
[void][IO.Directory]::CreateDirectory($polishNio)
$polishProcess = Start-Process -FilePath 'E:\Delelop\Java\jdk-21\bin\java.exe' -ArgumentList @(('"-Djdk.net.unixdomain.tmpdir=' + $polishNio + '"'),'-cp',('"'+$polishClasspath+'"'),'com.promptoptimizer.PromptOptimizerApplication','--server.port=9005','--spring.flyway.enabled=true') -WorkingDirectory (Join-Path $polishRepo 'services\api') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $PSScriptRoot 'backend.out.log') -RedirectStandardError (Join-Path $PSScriptRoot 'backend.err.log')
[pscustomobject]@{pid=$polishProcess.Id;port=9005;startedAt=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'process.json')
[pscustomobject]@{pid=$polishProcess.Id;port=9005} | ConvertTo-Json -Compress
