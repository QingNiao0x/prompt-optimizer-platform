param([Parameter(Mandatory = $true)][string] $EvidenceDirectory)

# 使用已授权的普通合成账户，凭据只传递给子进程；仍走真实CSRF、验证码和登录接口。
$ErrorActionPreference = 'Stop'
$acceptanceRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$acceptanceOutput = [IO.Path]::GetFullPath((Join-Path $acceptanceRepo $EvidenceDirectory))
$acceptanceAllowed = [IO.Path]::GetFullPath((Join-Path $acceptanceRepo 'tmp\prompt-comparison')) + [IO.Path]::DirectorySeparatorChar
if (-not $acceptanceOutput.StartsWith($acceptanceAllowed, [StringComparison]::OrdinalIgnoreCase)) { throw '验收目录必须在本仓库tmp/prompt-comparison内。' }
if (Test-Path -LiteralPath $acceptanceOutput) { throw '证据目录已经存在；请使用新目录。' }
[void][IO.Directory]::CreateDirectory($acceptanceOutput)
$acceptanceClasspathFile = Get-ChildItem -LiteralPath (Join-Path $acceptanceRepo 'tmp\backend-local-managed') -Filter runtime-classpath.txt -File -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $acceptanceClasspathFile) { throw '请先准备本地后端依赖清单。' }
$acceptanceClasspath = (Join-Path $acceptanceRepo 'services\api\target\classes') + ';' + [IO.File]::ReadAllText($acceptanceClasspathFile.FullName).Trim()
$acceptanceBuild = Join-Path $acceptanceOutput 'build'
[void][IO.Directory]::CreateDirectory($acceptanceBuild)
$acceptanceJava = (Get-Command java -ErrorAction Stop).Source
$acceptanceJavac = (Get-Command javac -ErrorAction Stop).Source
$env:PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER = 'qa_' + [Guid]::NewGuid().ToString('N').Substring(0, 20)
$env:PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD = 'Qa!' + [Guid]::NewGuid().ToString('N') + '8aZ'
Push-Location -LiteralPath $acceptanceRepo
try {
    & $acceptanceJavac -encoding UTF-8 --release 21 -cp $acceptanceClasspath -d $acceptanceBuild (Join-Path $PSScriptRoot 'java\LocalAcceptanceAccountProvisioner.java')
    if ($LASTEXITCODE -ne 0) { throw '合成账户工具编译失败。' }
    & $acceptanceJava -cp ($acceptanceBuild + ';' + $acceptanceClasspath) LocalAcceptanceAccountProvisioner (Join-Path $acceptanceOutput 'account.json')
    if ($LASTEXITCODE -ne 0) { throw '合成账户创建失败；没有更改已有账户。' }
    & node (Join-Path $PSScriptRoot 'local-acceptance-browser.mjs') $acceptanceOutput
    if ($LASTEXITCODE -ne 0) { throw '验收浏览器未登录。' }
} finally {
    [Environment]::SetEnvironmentVariable('PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD', $null, 'Process')
    [Environment]::SetEnvironmentVariable('PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER', $null, 'Process')
    Pop-Location
}
