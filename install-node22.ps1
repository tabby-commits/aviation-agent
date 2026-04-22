$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$version = '22.12.0'
$uri = "https://nodejs.org/dist/v$version/node-v$version-x64.msi"
$msi = Join-Path $env:TEMP "node-v$version-x64.msi"
Write-Output "Downloading Node.js v$version ..."
Invoke-WebRequest -Uri $uri -OutFile $msi
Write-Output "Installing Node.js v$version ..."
Start-Process msiexec.exe -ArgumentList @('/i', $msi, '/qn', '/norestart') -Wait -Verb runAs
Write-Output "Node.js v$version installed."
