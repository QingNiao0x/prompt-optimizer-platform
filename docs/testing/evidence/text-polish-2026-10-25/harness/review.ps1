$ErrorActionPreference = 'Stop'
foreach ($polishName in @('MODEL_API_KEY','MODEL_DEEPSEEK_API_KEY','MODEL_DEEPSEEK_ENDPOINT')) {
  $polishValue=[Environment]::GetEnvironmentVariable($polishName,'User')
  if ($polishValue) { [Environment]::SetEnvironmentVariable($polishName,$polishValue,'Process') }
}
try { & node tmp/text-polish-acceptance/review.mjs }
finally { [Environment]::SetEnvironmentVariable('MODEL_API_KEY',$null,'Process');[Environment]::SetEnvironmentVariable('MODEL_DEEPSEEK_API_KEY',$null,'Process') }
