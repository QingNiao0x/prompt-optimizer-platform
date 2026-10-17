param()
$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'E:\MyProject\prompt-optimizer-platform\apps\web'
if(Get-NetTCPConnection -State Listen -LocalPort 5174 -ErrorAction SilentlyContinue){throw 'Port 5174 occupied; no process stopped'}
$env:VITE_API_PROXY_TARGET='http://127.0.0.1:9001'
& 'E:/Delelop/nodejs/node.exe' 'node_modules/vite/bin/vite.js' '--host' '127.0.0.1' '--port' '5174' '--strictPort'
exit $LASTEXITCODE
