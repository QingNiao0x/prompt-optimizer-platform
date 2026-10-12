param([string]$Version='baseline',[string]$Cases='all',[string]$Round='1',[string]$Models='flash,pro')
$ErrorActionPreference='Stop'
foreach($evaluationName in @('MODEL_API_KEY','MODEL_DEEPSEEK_API_KEY')){$evaluationValue=[Environment]::GetEnvironmentVariable($evaluationName,'User');if($evaluationValue){[Environment]::SetEnvironmentVariable($evaluationName,$evaluationValue,'Process')}}
& node "$PSScriptRoot/evaluate.mjs" $Version $Cases $Round $Models
if($LASTEXITCODE -ne 0){throw 'Real evaluation failed; evidence retained'}
