$ErrorActionPreference = 'Stop'
$publicRoot = [IO.Path]::GetFullPath((Join-Path (Get-Location).Path 'docs/testing/evidence/me00a-candidate12-2026-10-14'))
# Compare actual locally configured secrets without emitting their values, hashes, or process arguments.
$secretValues = @([Environment]::GetEnvironmentVariables('User').GetEnumerator() |
    Where-Object { $_.Key -match '(?:PASSWORD|SECRET|API_KEY|TOKEN)' -and [string]$_.Value -and ([string]$_.Value).Length -ge 8 } |
    ForEach-Object { [string]$_.Value })
$credentialPath = Join-Path (Get-Location).Path 'tmp/me00a-candidate12-20261014/credential.json'
if (Test-Path -LiteralPath $credentialPath -PathType Leaf) {
    $secretValues += (Get-Content -LiteralPath $credentialPath -Raw | ConvertFrom-Json).password
}
$secretValues = @($secretValues | Where-Object { $_ } | Select-Object -Unique)
if ($secretValues.Count -lt 1) { throw 'No actual protected value available; cannot claim a real secret comparison' }
$files = @(rg --files $publicRoot)
foreach ($file in $files) {
    $content = [IO.File]::ReadAllText($file)
    foreach ($value in $secretValues) {
        if ($content.Contains($value)) { throw "Protected value present in public evidence: $file" }
    }
}
$report = @{ verifiedAt = [datetime]::UtcNow.ToString('o'); publicFilesChecked = $files.Count
    actualConfiguredValuesComparedPrivately = $true; matchingSecretValues = 0
    privateCredentialIncludedIfPresent = (Test-Path -LiteralPath $credentialPath -PathType Leaf)
    noSecretValuesOrHashesPrinted = $true }
$report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $publicRoot 'public-secret-check.json') -Encoding utf8
Write-Output "Public evidence checked: $($files.Count) files, zero configured-secret matches"
