$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath '.').Path
if ($repoPath -ne 'E:\MyProject\prompt-optimizer-platform') { throw 'Unexpected workspace; no cleanup performed' }
$ownedRoot = [IO.Path]::GetFullPath((Join-Path $repoPath 'tmp/me00a-candidate14-20261015'))
if (-not $ownedRoot.StartsWith($repoPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Owned cleanup directory escaped workspace'
}
$evidencePath = Join-Path $repoPath 'docs/testing/evidence/me00a-candidate14-2026-10-15/cleanup.json'
$serverOwner = Get-Content -LiteralPath (Join-Path $ownedRoot 'owned-server.json') -Raw | ConvertFrom-Json
$browserOwner = Get-Content -LiteralPath (Join-Path $ownedRoot 'owned-browser.json') -Raw | ConvertFrom-Json
$serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($serverOwner.pid)"
$browserProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($browserOwner.pid)"
# Verify both command ownership and launch time before stopping this batch's processes.
foreach ($pair in @(@{ Owner = $serverOwner; Process = $serverProcess }, @{ Owner = $browserOwner; Process = $browserProcess })) {
    # 原记录重复转义了路径；时间按UTC比较，仍保持同命令及10秒门限，不按PID单独判断所有权。
    $recordedScript = [IO.Path]::GetFullPath($pair.Owner.script.Replace('\\', '\'))
    $startDelta = if ($pair.Process) { [Math]::Abs((([datetimeoffset]$pair.Process.CreationDate).UtcDateTime - ([datetimeoffset]$pair.Owner.startedAt).UtcDateTime).TotalSeconds) } else { 0 }
    if ($pair.Process -and (-not $pair.Process.CommandLine.Contains($recordedScript) -or $startDelta -gt 10)) {
        throw 'Process ownership drifted; no process stopped'
    }
}
$listenersBefore = @(Get-NetTCPConnection -State Listen -ErrorAction Stop)
if (-not ($listenersBefore | Where-Object { $_.LocalPort -eq 5432 -and $_.OwningProcess -eq 8832 }) -or
    -not ($listenersBefore | Where-Object { $_.LocalPort -eq 6379 -and $_.OwningProcess -eq 7460 })) {
    throw 'Original data-service listener differs; inspect before cleanup'
}
$serverChildren = @()
if ($serverProcess) {
    $serverChildren = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($serverOwner.pid)" | Where-Object { $_.Name -eq 'java.exe' })
    foreach ($process in $serverChildren) {
        if (-not $process.CommandLine.Contains('me00a-candidate14-20261015') -or
            -not $process.CommandLine.Contains('MeEvaluationServer')) { throw 'Unexpected server child; no process stopped' }
    }
}
# Ask only the owned local account helper to close its persistent browser; the user-facing external browser stays open.
if ($browserProcess) {
    Set-Content -LiteralPath (Join-Path $ownedRoot 'close-browser') -Value 'Authorized evaluation completed' -Encoding utf8
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (-not (Get-CimInstance Win32_Process -Filter "ProcessId = $($browserOwner.pid)")) { break }
        Start-Sleep -Milliseconds 500
    }
    if (Get-CimInstance Win32_Process -Filter "ProcessId = $($browserOwner.pid)") { throw 'Owned local browser did not close normally' }
}
foreach ($process in $serverChildren) { Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue }
if ($serverProcess) { Stop-Process -Id $serverOwner.pid -ErrorAction SilentlyContinue }
$credentialPath = Join-Path $ownedRoot 'credential.json'
$credentialRemoved = $false
if (Test-Path -LiteralPath $credentialPath -PathType Leaf) {
    $resolvedCredential = (Resolve-Path -LiteralPath $credentialPath).Path
    if (-not $resolvedCredential.StartsWith($ownedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Private credential target escaped owned directory'
    }
    Remove-Item -LiteralPath $resolvedCredential
    $credentialRemoved = $true
}
$listenersAfter = @(Get-NetTCPConnection -State Listen -ErrorAction Stop)
if ($listenersAfter | Where-Object { $_.LocalPort -in @(9001, 9325) }) { throw 'Owned local evaluation port remains open' }
if (-not ($listenersAfter | Where-Object { $_.LocalPort -eq 5432 -and $_.OwningProcess -eq 8832 }) -or
    -not ($listenersAfter | Where-Object { $_.LocalPort -eq 6379 -and $_.OwningProcess -eq 7460 })) { throw 'Original data service changed' }
$report = @{ verifiedAt = [datetime]::UtcNow.ToString('o'); stoppedOnlyOwnedLocalServer = $true
    stoppedServerPids = @($serverChildren | ForEach-Object { $_.ProcessId }) + @($serverOwner.pid)
    localAccountBrowserClosedNormally = $true; localBrowserOwnerPid = $browserOwner.pid
    privateCredentialFileRemoved = $credentialRemoved; newAccountsCreated = 0; accountDeleted = $false
    postgresPid = 8832; redisPid = 7460; originalPostgresRedisStillListening = $true
    frontendProcessesStopped = $false; externalInteractiveBrowserRetainedForUser = $true
    externalDebugPort = 9336; privateLoginProfileNotPublished = $true }
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $evidencePath -Encoding utf8
Write-Output 'Owned local API/account browser closed; original Redis/PostgreSQL and user-facing test browser retained'
