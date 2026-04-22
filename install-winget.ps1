$ProgressPreference = 'SilentlyContinue'
$dest = Join-Path $env:TEMP 'appinstaller.msixbundle'
Invoke-WebRequest -Uri 'https://aka.ms/getwinget' -OutFile $dest
Add-AppxPackage -Path $dest
