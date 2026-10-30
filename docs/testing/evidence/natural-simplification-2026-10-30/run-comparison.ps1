[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
# 仅沿用本机已配置的官方模型密钥，不能把密钥放入参数、日志或评测原件。
$comparisonPreviousKey = $env:MODEL_API_KEY
try {
    if (-not $env:MODEL_API_KEY) { $env:MODEL_API_KEY = [Environment]::GetEnvironmentVariable('MODEL_API_KEY', 'User') }
    if (-not $env:MODEL_API_KEY) { throw 'Existing server-side model credential is unavailable.' }
    & node (Join-Path $PSScriptRoot 'compare.mjs') run
    if ($LASTEXITCODE -ne 0) { throw 'Finite comparison did not complete; retained reservations still consume budget.' }
} finally { $env:MODEL_API_KEY = $comparisonPreviousKey }
