param(
    [ValidateSet('ValidateJobs', 'RunJobs')]
    [string] $Action = 'ValidateJobs',
    [Parameter(Mandatory = $true)]
    [string] $JobsPath,
    [string] $ClasspathFile
)

# 验收执行器独立于后端，不启动 Spring/Flyway，不读取受保护配置或输出进程环境。
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$evaluationRepo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evaluationRoot = Join-Path $evaluationRepo 'tmp/prompt-output-evaluation'
$jobsAbsolute = (Resolve-Path -LiteralPath $JobsPath).ProviderPath
if (-not $jobsAbsolute.StartsWith($evaluationRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw '批次文件必须在 tmp/prompt-output-evaluation 内。'
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $evaluationJava = (Get-Command java -ErrorAction Stop).Source
    $evaluationJavac = (Get-Command javac -ErrorAction Stop).Source
} else {
    $evaluationJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $evaluationJavac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
}
$evaluationVersion = (& $evaluationJavac -version 2>&1 | Out-String).Trim()
if ($evaluationVersion -notmatch '^javac 21(?:\.|$)') { throw '评测执行器需要 JDK 21。' }

# 复用本地后端已经准备的依赖清单，避免重复下载、更新依赖或触发数据库迁移。
if ([string]::IsNullOrWhiteSpace($ClasspathFile)) {
    $managedRoot = Join-Path $evaluationRepo 'tmp/backend-local-managed'
    $classpathCandidates = @(Get-ChildItem -LiteralPath $managedRoot -Filter runtime-classpath.txt -File -Recurse |
        Sort-Object LastWriteTime -Descending)
    if ($classpathCandidates.Count -eq 0) { throw '未找到运行时依赖清单，请先准备本地后端，或传入 -ClasspathFile。' }
    $ClasspathFile = $classpathCandidates[0].FullName
}
$classpathAbsolute = (Resolve-Path -LiteralPath $ClasspathFile).ProviderPath
if (-not $classpathAbsolute.StartsWith($evaluationRepo + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    [System.IO.Path]::GetExtension($classpathAbsolute) -ne '.txt' -or
    [System.IO.Path]::GetFileName($classpathAbsolute) -notin @('runtime-classpath.txt', 'evaluation-classpath.txt')) {
    throw '依赖清单必须是仓库内的 runtime-classpath.txt 或 evaluation-classpath.txt。'
}
$evaluationClasspath = (Join-Path $evaluationRepo 'services/api/target/classes') + ';' +
    (Get-Content -LiteralPath $classpathAbsolute -Raw).Trim()
$compileDirectory = Join-Path $evaluationRoot ('build-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $compileDirectory | Out-Null
& $evaluationJavac -encoding UTF-8 --release 21 -cp $evaluationClasspath -d $compileDirectory (Join-Path $PSScriptRoot 'java/PromptOutputEvaluationRunner.java')
if ($LASTEXITCODE -ne 0) { throw '评测执行器编译失败。' }

Push-Location -LiteralPath $evaluationRepo
try {
    # 本机已核实的 JDK NIO 临时目录兼容措施，仅作用于本次子进程。
    $evaluationNio = Join-Path $evaluationRoot 'nio'
    New-Item -ItemType Directory -Path $evaluationNio -Force | Out-Null
    & $evaluationJava ('-Djdk.net.unixdomain.tmpdir=' + $evaluationNio) -cp ($compileDirectory + ';' + $evaluationClasspath) PromptOutputEvaluationRunner ('-' + $Action) $jobsAbsolute
    if ($LASTEXITCODE -ne 0) { throw '评测执行器停止；请检查上方脱敏原因代码。' }
} finally {
    Pop-Location
}
