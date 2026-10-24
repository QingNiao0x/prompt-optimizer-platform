#requires -Version 7.0
$ErrorActionPreference='Stop'
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$outputRoot=Join-Path $repoRoot 'tmp/natural-preparation-routing-2026-10-24/compile-preflight'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$dependencyFile=Get-ChildItem -LiteralPath (Join-Path $repoRoot 'tmp/backend-local-managed') -Filter runtime-classpath.txt -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $dependencyFile) { throw '缺少现有运行依赖清单。' }
$dependencies=([IO.File]::ReadAllText($dependencyFile.FullName).Trim().Split(';') | Where-Object { $_.EndsWith('.jar') }) -join ';'
$classpath=(Join-Path $repoRoot 'services/api/target/classes')+';'+$dependencies
& 'E:/Delelop/Java/jdk-21/bin/javac.exe' -encoding UTF-8 --release 21 -cp $classpath -d $outputRoot (Join-Path $PSScriptRoot 'NaturalPreparationRoutingEvaluationServer.java')
if ($LASTEXITCODE -ne 0) { throw '独立评测入口编译失败；未启动或调用模型。' }
[pscustomobject]@{status='COMPILED_ONLY';modelCalls=0;serviceStarted=$false;credentialsRead=$false} | ConvertTo-Json -Compress
