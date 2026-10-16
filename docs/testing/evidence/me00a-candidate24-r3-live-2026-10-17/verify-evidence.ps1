$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'E:\MyProject\prompt-optimizer-platform'
$evaluationPublic='docs/testing/evidence/me00a-candidate24-r3-live-2026-10-17'
$evaluationRoot=(Get-Location).Path
$evaluationManifest=Get-Content -LiteralPath 'docs/testing/evidence/me00a-candidate24-offline-2026-10-17/candidate24-source-r3.json' -Raw -Encoding UTF8 | ConvertFrom-Json
foreach($evaluationFile in $evaluationManifest.sourceFiles){
    if((Get-FileHash -LiteralPath $evaluationFile.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen source changed'}
}
foreach($evaluationFile in $evaluationManifest.runtimeFiles){
    $evaluationPath=Join-Path $evaluationManifest.runtimeSnapshot $evaluationFile.path
    if((Get-FileHash -LiteralPath $evaluationPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Frozen runtime changed'}
}
$evaluationTools=Get-Content -LiteralPath (Join-Path $evaluationPublic 'tools-frozen.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach($evaluationFile in $evaluationTools.files){
    if((Get-FileHash -LiteralPath $evaluationFile.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $evaluationFile.sha256){throw 'Execution tool changed after paid call freeze'}
}

# Only compare protected local values in memory. Neither matches nor values may be printed.
$evaluationProtected=[Collections.Generic.List[string]]::new()
$evaluationNames=[Collections.Generic.HashSet[string]]::new()
Get-ChildItem -LiteralPath 'services/api/src/main/resources' -Filter 'application*.yml' | ForEach-Object {
    foreach($evaluationMatch in [regex]::Matches([IO.File]::ReadAllText($_.FullName),'\$\{([A-Z][A-Z0-9_]*(?:PASSWORD|SECRET|API_KEY|TOKEN))')){
        [void]$evaluationNames.Add($evaluationMatch.Groups[1].Value)
    }
}
foreach($evaluationName in $evaluationNames){
    $evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User')
    if($evaluationValue -and $evaluationValue.Length -ge 12){$evaluationProtected.Add($evaluationValue)}
}
$evaluationActor=Get-Content -LiteralPath 'tmp/local-acceptance/account.json' -Raw -Encoding UTF8 | ConvertFrom-Json
foreach($evaluationField in 'identifier','userId','tenantId','workspaceId'){
    $evaluationValue=[string]$evaluationActor.$evaluationField
    if($evaluationValue.Length -ge 6){$evaluationProtected.Add($evaluationValue)}
}
foreach($evaluationFile in (Get-ChildItem -LiteralPath $evaluationPublic -Recurse -File)){
    if($evaluationFile.Extension -eq '.json'){
        $null=Get-Content -LiteralPath $evaluationFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    if($evaluationFile.Extension -eq '.png'){continue}
    $evaluationText=[IO.File]::ReadAllText($evaluationFile.FullName)
    foreach($evaluationValue in $evaluationProtected){
        if($evaluationText.Contains($evaluationValue)){throw 'Protected local value found in public archive; no value printed'}
    }
    if($evaluationText -match 'Bearer\s+[A-Za-z0-9._-]{12,}|"Authorization"\s*:|sk-[A-Za-z0-9]{24,}'){
        throw 'Protected credential pattern found in public archive'
    }
}
$evaluationProtected.Clear()
$evaluationValue=$null

$evaluationTraces=@(Get-ChildItem -LiteralPath 'tmp/me00a-20261012/traces' -Filter '*.json' | Sort-Object Name)
for($evaluationIndex=0;$evaluationIndex -lt $evaluationTraces.Count;$evaluationIndex++){
    if([int]$evaluationTraces[$evaluationIndex].BaseName -ne $evaluationIndex+1){throw 'Non-contiguous ledger'}
}
$evaluationAll=@($evaluationTraces | ForEach-Object {Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json})
$evaluationProtocol=Get-Content -LiteralPath 'tmp/me00a-20261012/protocol.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$evaluationCandidate=@($evaluationAll | Where-Object phase -eq 'candidateValidation').Count
if($evaluationTraces.Count -ne 327 -or $evaluationCandidate -ne 257 -or $evaluationProtocol.activeBatch.status -ne 'CLOSED'){
    throw 'Final ledger or batch status changed'
}
if($evaluationProtocol.maximumActualModelCalls -ne 338 -or $evaluationProtocol.phaseLimits.baseline -ne 80 -or $evaluationProtocol.phaseLimits.candidateValidation -ne 258){
    throw 'Formal limits changed'
}
$evaluationIgnored=@(& git check-ignore 'tmp/local-acceptance/account.json' 'tmp/local-acceptance/credential.dpapi' 'tmp/me00a-candidate24-r3-live-20261017/real/Q05-plan/history.private.json')
if($evaluationIgnored.Count -ne 3){throw 'Private paths must remain ignored'}
$evaluationResult=[ordered]@{kind='FINAL_LOCAL_EVIDENCE_VERIFICATION';modelCalls=0;sourceFilesVerified=$evaluationManifest.sourceFiles.Count;runtimeFilesVerified=$evaluationManifest.runtimeFiles.Count;executionToolsVerified=$evaluationTools.files.Count;publicJsonParsed=$true;protectedLocalValuesDetected=0;privatePathsIgnored=3;ledgerContinuous=$true;actualTotal=327;baselineActual=70;candidateActual=257;batchClosed=$true;unusedClosedNotTransferable=1;formalLimits=@{total=338;baseline=80;candidateValidation=258}}
[IO.File]::WriteAllText((Join-Path $evaluationRoot ($evaluationPublic+'/final-verification.json')),($evaluationResult | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$evaluationRows=@(Get-ChildItem -LiteralPath $evaluationPublic -Recurse -File | Where-Object Name -ne 'evidence-manifest.json' | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=$_.FullName.Substring($evaluationRoot.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $evaluationRoot ($evaluationPublic+'/evidence-manifest.json')),(@{candidate='candidate24-r3';files=$evaluationRows;bytePreservation='gitattributes -text -whitespace';containsOriginalSyntheticVisibleResponses=$true} | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$evaluationResult | ConvertTo-Json -Depth 8 -Compress
