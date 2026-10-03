[CmdletBinding()]
param(
    [switch]$Apply,
    [Nullable[int]]$ExpectedCount
)

# 仅用于误入开发 journal 的 TestActors 夹具；默认只检查，不处理真实账号记录。
# 原始 JSON 原样转存并记录 SHA-256，不删除文件、不制造 ack，也不写业务数据库。
$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$journalRoot = Join-Path $workspaceRoot 'services/api/data/analytics-journal'
$fixturePath = Join-Path $workspaceRoot 'services/api/src/test/java/com/promptoptimizer/identity/support/TestActors.java'
$fixtureSource = Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8
$tenantMatch = [regex]::Match($fixtureSource, 'TENANT_ID\s*=\s*UUID\.fromString\("([0-9a-fA-F-]{36})"\)')
if (-not $tenantMatch.Success) { throw 'Cannot determine the fixed TestActors tenant; no files changed.' }
$fixtureTenant = [guid]::Parse($tenantMatch.Groups[1].Value)

# 每个实际源路径和目标路径都必须留在本仓库 journal 中；拒绝链接目录，避免路径跳转。
function Assert-JournalPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($journalRoot) + [IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing a path outside the journal.'
    }
    for ($parent = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($absolute)); $null -ne $parent; $parent = $parent.Parent) {
        if ($parent.Exists -and ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refusing a linked journal directory.'
        }
    }
    return $absolute
}

$candidates = @(
    if (Test-Path -LiteralPath $journalRoot -PathType Container) {
        foreach ($file in Get-ChildItem -LiteralPath $journalRoot -File -Filter '*.json') {
            $source = Assert-JournalPath $file.FullName
            if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a linked event file.' }
            if (Test-Path -LiteralPath (Join-Path $journalRoot ($file.BaseName + '.ack'))) { continue }
            try { $event = Get-Content -LiteralPath $source -Raw -Encoding UTF8 | ConvertFrom-Json }
            catch { throw 'Invalid event JSON; preserve it for separate investigation.' }
            if ([guid]::Parse($event.tenantId) -ne $fixtureTenant) { continue }
            if ($file.BaseName -ne ([guid]::Parse($event.id)).ToString()) { throw 'Event filename does not match its ID.' }
            [pscustomobject]@{ Source = $source; FileName = $file.Name; Sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash }
        }
    }
)

if (-not $Apply) {
    [pscustomobject]@{ Status = 'DRY_RUN'; MatchingUnacknowledgedTestEvents = $candidates.Count } | ConvertTo-Json -Compress
    return
}
if ($null -eq $ExpectedCount -or $ExpectedCount -lt 1 -or $candidates.Count -ne $ExpectedCount) {
    throw 'Apply requires the reviewed nonzero ExpectedCount to match exactly; no files changed.'
}

$archive = Assert-JournalPath (Join-Path $journalRoot ('quarantine/test-fixtures-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N')))
if (Test-Path -LiteralPath $archive) { throw 'Archive already exists; refusing to overwrite.' }
New-Item -ItemType Directory -Path $archive | Out-Null
$manifest = [ordered]@{
    CreatedAt = [DateTime]::UtcNow.ToString('o')
    Reason = 'TEST_ACTORS_FIXTURES_IN_RUNTIME_JOURNAL'
    Count = $candidates.Count
    Events = @($candidates | Select-Object FileName,Sha256)
}
$manifest | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $archive 'manifest.json') -Encoding utf8 -NoClobber

foreach ($candidate in $candidates) {
    $source = Assert-JournalPath $candidate.Source
    $destination = Assert-JournalPath (Join-Path $archive $candidate.FileName)
    $ack = [IO.Path]::ChangeExtension($source, '.ack')
    # 移动前复查确认状态和哈希，防止误处理检查之后刚成功入库或被修改的记录。
    if ((Test-Path -LiteralPath $ack) -or (Test-Path -LiteralPath $destination) -or
            (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $candidate.Sha256) {
        throw 'Event state changed; stopped. The manifest records planned files and all originals remain preserved.'
    }
    Move-Item -LiteralPath $source -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $candidate.Sha256) {
        throw 'Archive hash verification failed; stop and inspect the preserved file.'
    }
}
[pscustomobject]@{ CompletedAt = [DateTime]::UtcNow.ToString('o'); Count = $candidates.Count } |
    ConvertTo-Json | Out-File -LiteralPath (Join-Path $archive 'completed.json') -Encoding utf8 -NoClobber
[pscustomobject]@{ Status = 'APPLIED'; PreservedTestEvents = $candidates.Count; Archive = $archive } | ConvertTo-Json -Compress
