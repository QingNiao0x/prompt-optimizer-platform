<#
.SYNOPSIS
  从 .env.local 安全加载本地密钥并启动后端。
.DESCRIPTION
  .env.local 已被 .gitignore 忽略，只保存在本机，不会提交到 Git。
  脚本把其中的键值注入当前进程环境变量后，再调用 Maven 启动 Spring Boot 后端。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\start-backend-local.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
$envFile = Join-Path $repoRoot '.env.local'

if (Test-Path -LiteralPath $envFile) {
    # .env.local 是可选的；存在时只注入当前进程，不写入任何项目文件。
    $envLines = Get-Content -LiteralPath $envFile
    foreach ($envLine in $envLines) {
        $parts = $envLine -split '=', 2
        if ($parts.Count -eq 2 -and -not $parts[0].Trim().StartsWith('#')) {
            $name = $parts[0].Trim()
            $value = $parts[1].Trim().Trim([char]34).Trim([char]39)
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Host ".env.local not found; using current user environment variables." -ForegroundColor Cyan
}

if (-not $env:MODEL_API_KEY) {
    Write-Host "MODEL_API_KEY is not configured; Spring Boot validation will fail." -ForegroundColor Red
    exit 1
}

if (-not $env:API_KEY_ENCRYPTION_SECRET) {
    Write-Host "API_KEY_ENCRYPTION_SECRET is not configured; set it before saving provider settings." -ForegroundColor Yellow
}

$mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Host "mvn.cmd was not found; please add Maven to PATH." -ForegroundColor Red
    exit 1
}

Set-Location (Join-Path $repoRoot 'services\api')
Write-Host "Starting backend; the startup banner will appear in the logs..." -ForegroundColor Cyan
& $mvn.Source '-s' '..\..\.mvn\settings.xml' 'spring-boot:run'
