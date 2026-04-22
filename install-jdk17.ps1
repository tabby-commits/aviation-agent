$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$msi = Join-Path $env:TEMP 'microsoft-jdk-17.msi'
Invoke-WebRequest -Uri 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.msi' -OutFile $msi
Start-Process msiexec.exe -ArgumentList @('/i', $msi, '/qn', '/norestart', 'ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith') -Wait -Verb runAs
# Detect install dir
$installRoot = 'C:\Program Files\Microsoft'
$jdkDir = Get-ChildItem -Path $installRoot -Directory -Filter 'jdk-17*' | Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdkDir) { Write-Error 'JDK 17 install directory not found'; }
$javaHome = $jdkDir.FullName
# Set JAVA_HOME user-level
[Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'User')
# Append bin to user PATH if not present
$binPath = Join-Path $javaHome 'bin'
$currentPath = [Environment]::GetEnvironmentVariable('Path','User')
if ($currentPath -notlike "*$binPath*") {
    $newPath = "$currentPath;$binPath"
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
}
Write-Output "JAVA_HOME set to $javaHome"
Write-Output "JDK 17 installed. Please restart terminal to use new PATH."
