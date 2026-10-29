param(
    [Parameter(Mandatory=$true)][string]$StagePath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [string]$RuntimeClasses,
    [string]$DependencyClasspathFile,
    [string[]]$JobId=@(),
    [string]$JavaHome='E:\Delelop\Java\jdk-21'
)
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../../../../../')).Path
$stage=(Resolve-Path -LiteralPath $StagePath).Path
$stageName=Split-Path -Leaf $stage
$runtimeBase=Join-Path $repository "tmp/natural-useful-delivery-2026-10-28/$stageName"
if(-not $RuntimeClasses){$RuntimeClasses=Join-Path $runtimeBase 'runtime/classes'}
if(-not $DependencyClasspathFile){$DependencyClasspathFile=Join-Path $runtimeBase 'dependency-classpath.txt'}
$runtime=(Resolve-Path -LiteralPath $RuntimeClasses).Path
$dependencies=(Get-Content -Raw -LiteralPath $DependencyClasspathFile).Trim()
$compiled=Join-Path $repository "tmp/natural-useful-delivery-2026-10-28/offline-result-validation/$stageName/classes"
New-Item -ItemType Directory -Path $compiled -Force | Out-Null
$classpath=$runtime+';'+$dependencies
$jobs=Get-Content -Raw -LiteralPath (Join-Path $stage 'jobs.json') | ConvertFrom-Json
$registryProtocol=@($jobs | Where-Object { $_.version -in @('49','50','51') -and $_.approvedEnhancementInput }).Count -gt 0
$sources=@((Join-Path $PSScriptRoot 'NaturalModelResultReplay.java'))
$entry='com.promptoptimizer.provider.infrastructure.openai.NaturalModelResultReplay'
if($registryProtocol){
    $sources+=(Join-Path $PSScriptRoot 'NaturalRegistry49ResultReplay.java')
    $entry='com.promptoptimizer.provider.infrastructure.openai.NaturalRegistry49ResultReplay'
}
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d $compiled @sources
if($LASTEXITCODE -ne 0){throw 'Offline validator compilation failed.'}
$arguments=@('-Djdk.net.URLClassPath.disableClassPathURLCheck=true','-cp',($compiled+';'+$classpath),$entry,'--repo',$repository,'--stage',$stage,'--output',([System.IO.Path]::GetFullPath($OutputPath)))
foreach($id in $JobId){$arguments+=@('--job',$id)}
# No classpath, environment, authentication configuration or upstream headers are printed or copied.
& (Join-Path $JavaHome 'bin/java.exe') @arguments
if($LASTEXITCODE -ne 0){throw 'Offline validator could not complete; no successful candidate status may be inferred.'}
