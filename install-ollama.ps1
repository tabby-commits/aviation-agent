$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$dst = Join-Path $env:TEMP 'OllamaSetup.exe'
Invoke-WebRequest -Uri 'https://ollama.com/download/OllamaSetup.exe' -OutFile $dst
Start-Process -FilePath $dst -ArgumentList '/S' -Wait
Write-Output 'Ollama installer executed. If service not running, please log out/in or start Ollama manually.'
