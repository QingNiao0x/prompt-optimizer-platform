$ErrorActionPreference = 'Stop'
$polishSecure = ConvertTo-SecureString ([IO.File]::ReadAllText('tmp/local-acceptance/credential.dpapi').Trim())
$env:POLISH_ACCEPTANCE_PASSWORD = [Net.NetworkCredential]::new('', $polishSecure).Password
try { & node tmp/text-polish-acceptance/browser.mjs }
finally { [Environment]::SetEnvironmentVariable('POLISH_ACCEPTANCE_PASSWORD',$null,'Process'); $polishSecure.Dispose() }
