<#
.SYNOPSIS
  管理本项目的本地后端进程，并在启动后检查健康状态。
.DESCRIPTION
  只接管命令行同时包含本仓库路径和应用主类的 Java 进程，不停止其他服务。
  密钥从当前进程环境继承；不读取受保护配置文件，也不保存环境变量或启动参数。
  先准备编译结果和运行时依赖，再停止旧进程。自动启动禁用 Flyway，迁移仍需另行确认。
.EXAMPLE
  .\scripts\manage-backend-local.ps1 -Action Restart
.EXAMPLE
  .\scripts\manage-backend-local.ps1 -Action Status
#>
[CmdletBinding()]
param(
    [ValidateSet('Status', 'Start', 'Restart')]
    [string] $Action = 'Status',
    [ValidateRange(1024, 65535)]
    [int] $Port = 9000,
    [ValidateRange(10, 300)]
    [int] $HealthTimeoutSeconds = 120,
    [switch] $SkipCompile,
    [switch] $CheckOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$apiDirectory = Join-Path $repoRoot 'services\api'
$applicationClass = 'com.promptoptimizer.PromptOptimizerApplication'

# 端口可能同时列出 IPv4 和 IPv6；按 PID 去重，拒绝接管来源不明的监听者。
function Get-BackendListener {
    $listenerIds = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
    if ($listenerIds.Count -gt 1) {
        throw '该端口存在多个不同进程，拒绝自动接管。'
    }
    if ($listenerIds.Count -eq 0) { return $null }
    $listener = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $listenerIds[0])
    if (-not $listener) { return $null }
    $launchText = [string] $listener.CommandLine
    $workspaceMatch = $launchText.IndexOf($repoRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0
    $classMatch = $launchText -match ('(?:^|\s)' + [regex]::Escape($applicationClass) + '(?:\s|$)')
    return [pscustomobject]@{
        ProcessId = [int] $listener.ProcessId
        StartedAt = $listener.CreationDate.ToString('o')
        IsBackend = ($listener.Name -eq 'java.exe' -and $workspaceMatch -and $classMatch)
        JavaExecutable = $listener.ExecutablePath
    }
}

# 不打印网络异常正文或认证数据；健康接口只返回固定的服务元数据。
function Test-BackendHealth {
    try {
        $health = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $Port + '/api/v1/health') -TimeoutSec 3
        return ($health.data.status -eq 'UP' -and $health.data.service -eq 'prompt-optimizer-api')
    } catch { return $false }
}

function Write-BackendStatus {
    param($Listener, [string] $State)
    [pscustomobject]@{
        state = $State
        port = $Port
        pid = if ($Listener) { $Listener.ProcessId } else { $null }
        startedAt = if ($Listener) { $Listener.StartedAt } else { $null }
        belongsToWorkspace = [bool] ($Listener -and $Listener.IsBackend)
        healthy = [bool] ($Listener -and (Test-BackendHealth))
    } | ConvertTo-Json -Compress
}

$existing = Get-BackendListener
if ($Action -eq 'Status') {
    Write-BackendStatus $existing 'STATUS'
    exit 0
}
if ($existing -and -not $existing.IsBackend) {
    throw '端口被其他或无法核实的进程占用；未停止任何进程。'
}
if ($existing -and $Action -eq 'Start' -and -not $CheckOnly) {
    Write-BackendStatus $existing 'ALREADY_RUNNING'
    exit 0
}

# 仅检查必要环境项是否存在；不打开 .env，不输出或复制其值。
foreach ($requiredName in @('MODEL_API_KEY', 'API_KEY_ENCRYPTION_SECRET')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredName))) {
        throw ('当前进程缺少环境变量 ' + $requiredName + '；旧服务未停止。')
    }
}
$javaExecutable = if ($existing -and $existing.IsBackend) {
    $existing.JavaExecutable
} elseif (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    (Get-Command java.exe -ErrorAction Stop).Source
}
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw 'Java 可执行文件不存在；旧服务未停止。'
}
$mavenCommand = Get-Command mvn.cmd -ErrorAction Stop
# 跳过编译时也检查运行时版本，避免旧服务停止后才发现 Java 主版本不兼容。
$versionInfo = [Diagnostics.ProcessStartInfo]::new($javaExecutable, '-version')
$versionInfo.UseShellExecute = $false
$versionInfo.CreateNoWindow = $true
$versionInfo.RedirectStandardError = $true
$versionInfo.RedirectStandardOutput = $true
$versionProcess = [Diagnostics.Process]::Start($versionInfo)
try {
    $versionOutput = $versionProcess.StandardError.ReadToEnd() + $versionProcess.StandardOutput.ReadToEnd()
    $versionProcess.WaitForExit()
    if ($versionProcess.ExitCode -ne 0 -or $versionOutput -notmatch 'version "21(?:\.|"|-)') {
        throw '启动脚本要求 Java 21；旧服务未停止。'
    }
} finally { $versionProcess.Dispose() }
# 启动环境的 NIO 故障必须在停止旧服务之前发现，不能借健康接口的旧响应冒充可启动。
# 此机 JDK 的选择器在默认 TEMP 中创建本机 socket 时连接失败；独立目录对照通过。
# 检查与后端使用同一个仓库内目录，仅设置子进程参数，不修改全局 TEMP 或 NIO 实现。
$nioTempDirectory = [IO.Path]::GetFullPath((Join-Path $repoRoot 'tmp\backend-local-managed\nio'))
$managedDirectoryPrefix = [IO.Path]::GetFullPath((Join-Path $repoRoot 'tmp\backend-local-managed')) + [IO.Path]::DirectorySeparatorChar
if (-not $nioTempDirectory.StartsWith($managedDirectoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw '本地 NIO 临时目录越界；旧服务未停止。'
}
[void] [IO.Directory]::CreateDirectory($nioTempDirectory)
$nioJvmArgument = '"-Djdk.net.unixdomain.tmpdir=' + $nioTempDirectory + '"'
$probeSource = Join-Path $PSScriptRoot 'java\BackendNioPreflight.java'
$probeInfo = [Diagnostics.ProcessStartInfo]::new($javaExecutable, ($nioJvmArgument + ' --source 21 "' + $probeSource + '"'))
$probeInfo.UseShellExecute = $false
$probeInfo.CreateNoWindow = $true
$probeInfo.RedirectStandardError = $true
$probeInfo.RedirectStandardOutput = $true
$probeProcess = [Diagnostics.Process]::Start($probeInfo)
try {
    $probeError = $probeProcess.StandardError.ReadToEnd()
    $probeOutput = $probeProcess.StandardOutput.ReadToEnd()
    $probeProcess.WaitForExit()
    if ($probeProcess.ExitCode -ne 0) {
        throw '当前启动环境的 Java NIO 本机连接检查失败；未停止现有后端。请核对本地临时目录与 Java 环境。'
    }
} finally { $probeProcess.Dispose() }
if ($CheckOnly) {
    [pscustomobject]@{
        state = 'CHECKED'
        action = $Action
        port = $Port
        existingPid = if ($existing) { $existing.ProcessId } else { $null }
        belongsToWorkspace = [bool] ($existing -and $existing.IsBackend)
        compile = -not $SkipCompile
        nioReady = $true
        migrationsEnabled = $false
        readsProtectedConfigFiles = $false
    } | ConvertTo-Json -Compress
    exit 0
}

$runId = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH-mm-ss-fffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runDirectory = [IO.Path]::GetFullPath((Join-Path $repoRoot ('tmp\backend-local-managed\' + $runId)))
$allowedDirectory = [IO.Path]::GetFullPath((Join-Path $repoRoot 'tmp\backend-local-managed')) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($allowedDirectory, [StringComparison]::OrdinalIgnoreCase)) {
    throw '本地运行目录越界；旧服务未停止。'
}
[void] [IO.Directory]::CreateDirectory($runDirectory)
$classpathFile = Join-Path $runDirectory 'runtime-classpath.txt'
$buildLog = Join-Path $runDirectory 'build.log'
$classpathArguments = @('-q', '--no-transfer-progress')
$mavenSettings = Join-Path $repoRoot '.mvn\settings.xml'
if (Test-Path -LiteralPath $mavenSettings) { $classpathArguments += @('-s', $mavenSettings) }
if (-not $SkipCompile) { $classpathArguments += 'compile' }
$classpathArguments += @('dependency:build-classpath', '-DincludeScope=runtime', ('-Dmdep.outputFile=' + $classpathFile))

# 编译或依赖准备失败时保持原进程运行。每次使用独立日志目录，保留历史证据。
Push-Location -LiteralPath $apiDirectory
try {
    & $mavenCommand.Source @classpathArguments *> $buildLog
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile)) {
        throw ('编译或运行时依赖准备失败；旧服务未停止。日志：' + $buildLog)
    }
} finally { Pop-Location }
$classesDirectory = Join-Path $apiDirectory 'target\classes'
if (-not (Test-Path -LiteralPath (Join-Path $classesDirectory 'com\promptoptimizer\PromptOptimizerApplication.class'))) {
    throw '应用编译产物不存在；旧服务未停止。'
}
$classpath = $classesDirectory + ';' + [IO.File]::ReadAllText($classpathFile).Trim()
if ($classpath.Contains('"') -or $classpath.Contains("`r") -or $classpath.Contains("`n")) {
    throw '运行时依赖路径格式非法；旧服务未停止。'
}

# 准备期间 PID 可能变化：再次核对进程归属与创建时间，防止误停 PID 复用后的其他进程。
$beforeStop = Get-BackendListener
if ($beforeStop -and (-not $beforeStop.IsBackend -or -not $existing -or
    $beforeStop.ProcessId -ne $existing.ProcessId -or $beforeStop.StartedAt -ne $existing.StartedAt)) {
    throw '后端监听进程已变化；未停止任何进程，请重新检查。'
}
if ($beforeStop) {
    Stop-Process -Id $beforeStop.ProcessId -ErrorAction Stop
    $stopDeadline = [DateTime]::UtcNow.AddSeconds(15)
    while ((Get-BackendListener) -and [DateTime]::UtcNow -lt $stopDeadline) { Start-Sleep -Milliseconds 200 }
    if (Get-BackendListener) { throw '原服务尚未释放端口，未启动第二个服务。' }
}

$stdoutLog = Join-Path $runDirectory 'stdout.log'
$stderrLog = Join-Path $runDirectory 'stderr.log'
$launchArguments = @('-Dfile.encoding=UTF-8', $nioJvmArgument, '-classpath', ('"' + $classpath + '"'), $applicationClass,
    ('--server.port=' + $Port), '--spring.flyway.enabled=false')
$startedProcess = Start-Process -FilePath $javaExecutable -ArgumentList $launchArguments `
    -WorkingDirectory $apiDirectory -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog

$deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
do {
    $startedProcess.Refresh()
    if ($startedProcess.HasExited) {
        throw ('新后端启动失败；日志：' + $stdoutLog + ' 和 ' + $stderrLog)
    }
    $current = Get-BackendListener
    if ($current -and $current.ProcessId -eq $startedProcess.Id -and $current.IsBackend -and (Test-BackendHealth)) {
        $record = [ordered]@{
            port = $Port
            pid = $current.ProcessId
            startedAt = $current.StartedAt
            action = $Action
            previousPid = if ($beforeStop) { $beforeStop.ProcessId } else { $null }
            compiled = -not $SkipCompile
            migrationsEnabled = $false
            healthy = $true
            stdoutLog = $stdoutLog
            stderrLog = $stderrLog
        }
        $recordFile = Join-Path $runDirectory 'launch.json'
        $recordStream = [IO.File]::Open($recordFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            $recordBytes = [Text.Encoding]::UTF8.GetBytes(($record | ConvertTo-Json -Depth 4))
            $recordStream.Write($recordBytes, 0, $recordBytes.Length)
        } finally { $recordStream.Dispose() }
        $record | ConvertTo-Json -Compress
        exit 0
    }
    Start-Sleep -Milliseconds 300
} while ([DateTime]::UtcNow -lt $deadline)
throw ('新后端未在规定时间内通过健康检查；保留进程及日志供排查：' + $runDirectory)
