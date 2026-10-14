$ErrorActionPreference = 'Stop'
$repoPath = (Get-Location).Path
$ownedRoot = [IO.Path]::GetFullPath((Join-Path $repoPath 'tmp/me00a-candidate12-20261014'))
$evidencePath = Join-Path $repoPath 'docs/testing/evidence/me00a-candidate12-2026-10-14/cleanup.json'
$serverOwner = Get-Content -LiteralPath (Join-Path $ownedRoot 'owned-server.json') -Raw | ConvertFrom-Json
$browserOwner = Get-Content -LiteralPath (Join-Path $ownedRoot 'owned-browser.json') -Raw | ConvertFrom-Json
$serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($serverOwner.pid)"
$browserProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($browserOwner.pid)"
# PID alone does not authorize termination: verify this batch's command and original launch time.
if ($serverProcess -and (-not $serverProcess.CommandLine.Contains($serverOwner.script) -or
    [Math]::Abs(($serverProcess.CreationDate - [datetime]$serverOwner.startedAt).TotalSeconds) -gt 10)) {
    throw 'Server ownership drifted; no process stopped'
}
if ($browserProcess -and (-not $browserProcess.CommandLine.Contains($browserOwner.script) -or
    [Math]::Abs(($browserProcess.CreationDate - [datetime]$browserOwner.startedAt).TotalSeconds) -gt 10)) {
    throw 'Browser ownership drifted; no process stopped'
}
$serverChildren = @()
if ($serverProcess) {
    $serverChildren = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($serverOwner.pid)" | Where-Object { $_.Name -eq 'java.exe' })
    foreach ($process in $serverChildren) {
        if (-not $process.CommandLine.Contains('me00a-candidate12-20261014') -or
            -not $process.CommandLine.Contains('MeEvaluationServer')) { throw 'Unexpected Java child; no process stopped' }
    }
}
# The owner closes its persistent browser normally; unrelated Chrome windows are never selected.
if ($browserProcess) {
    Set-Content -LiteralPath (Join-Path $ownedRoot 'close-browser') -Value 'Authorized evaluation completed' -Encoding utf8
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (-not (Get-CimInstance Win32_Process -Filter "ProcessId = $($browserOwner.pid)")) { break }
        Start-Sleep -Milliseconds 500
    }
    if (Get-CimInstance Win32_Process -Filter "ProcessId = $($browserOwner.pid)") {
        throw 'Owned browser did not close normally; inspect before forced termination'
    }
}
foreach ($process in $serverChildren) { Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue }
if ($serverProcess) { Stop-Process -Id $serverOwner.pid -ErrorAction SilentlyContinue }
$credentialPath = Join-Path $ownedRoot 'credential.json'
$credentialRemoved = $false
if (Test-Path -LiteralPath $credentialPath -PathType Leaf) {
    $resolvedCredential = (Resolve-Path -LiteralPath $credentialPath).Path
    if (-not $resolvedCredential.StartsWith($ownedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Private credential file escaped owned directory'
    }
    Remove-Item -LiteralPath $resolvedCredential
    $credentialRemoved = $true
}
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue)
if ($listeners | Where-Object { $_.LocalPort -in @(9001, 9325) }) { throw 'Owned evaluation port remains open' }
if (-not ($listeners | Where-Object { $_.LocalPort -eq 5432 -and $_.OwningProcess -eq 8860 }) -or
    -not ($listeners | Where-Object { $_.LocalPort -eq 6379 -and $_.OwningProcess -eq 5624 })) {
    throw 'Original PostgreSQL/Redis listener changed; do not claim unchanged services'
}
$report = @{ verifiedAt = [datetime]::UtcNow.ToString('o'); stoppedOnlyOwnedServer = $true
    stoppedServerPids = @($serverChildren | ForEach-Object { $_.ProcessId }) + @($serverOwner.pid)
    browserClosedNormally = $true; browserOwnerPid = $browserOwner.pid
    privateCredentialFileRemoved = $credentialRemoved; newAccountsCreated = 0; accountDeleted = $false
    postgresPid = 8860; redisPid = 5624; originalPostgresRedisStillListening = $true
    frontend5173ObservedListeningAtEnd = [bool]($listeners | Where-Object { $_.LocalPort -eq 5173 })
    preexistingJavaPids25244And9440StoppedByThisScript = $false }
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $evidencePath -Encoding utf8
Write-Output 'Owned evaluation server/browser closed; PostgreSQL/Redis retained; private credential removed'
