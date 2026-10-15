$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath '.').Path
if ($repoPath -ne 'E:\MyProject\prompt-optimizer-platform') { throw 'Unexpected workspace' }
$publicRoot = [IO.Path]::GetFullPath((Join-Path $repoPath 'docs/testing/evidence/me00a-candidate13-2026-10-15'))
$harnessRoot = Join-Path $repoPath 'tmp/me00a-candidate13-20261014/harness'
if (-not $publicRoot.StartsWith($repoPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence directory escaped workspace' }
# Keep protected values only in this process while removing the private credential file; never emit their values or hashes.
$secretValues = @([Environment]::GetEnvironmentVariables('User').GetEnumerator() |
    Where-Object { $_.Key -match '(?:PASSWORD|SECRET|API_KEY|TOKEN)' -and [string]$_.Value -and ([string]$_.Value).Length -ge 8 } |
    ForEach-Object { [string]$_.Value })
$credentialPath = Join-Path $repoPath 'tmp/me00a-candidate13-20261014/credential.json'
$privateCredentialCompared = Test-Path -LiteralPath $credentialPath -PathType Leaf
if ($privateCredentialCompared) { $secretValues += (Get-Content -LiteralPath $credentialPath -Raw | ConvertFrom-Json).password }
$secretValues = @($secretValues | Where-Object { $_ } | Select-Object -Unique)
if ($secretValues.Count -lt 1) { throw 'No protected reference values; cannot claim actual secret comparison' }
& (Join-Path $harnessRoot 'cleanup-owned.ps1')
$files = @(rg --files $publicRoot)
foreach ($file in $files) {
    $content = [IO.File]::ReadAllText($file)
    foreach ($value in $secretValues) { if ($content.Contains($value)) { throw "Protected value present in public evidence: $file" } }
}
$report = @{ verifiedAt = [datetime]::UtcNow.ToString('o'); publicFilesChecked = $files.Count
    actualConfiguredValuesComparedPrivately = $true; privateCredentialCapturedInMemoryBeforeDeletion = $privateCredentialCompared
    matchingSecretValues = 0; noSecretValuesOrHashesPrinted = $true; privateCredentialFileExistsAfterCleanup = (Test-Path -LiteralPath $credentialPath -PathType Leaf) }
$report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $publicRoot 'public-secret-check.json') -Encoding utf8
node (Join-Path $harnessRoot 'final-verification.mjs') index
if ($LASTEXITCODE -ne 0) { throw 'Evidence index failed' }
node (Join-Path $harnessRoot 'final-verification.mjs') verify
if ($LASTEXITCODE -ne 0) { throw 'Final evidence verification failed' }
Write-Output "Public evidence checked: $($files.Count) files; zero protected-value matches"
