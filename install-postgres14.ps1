param(
    [string]$SuperPassword = '22374493',
    [string]$Port = '5432',
    [string]$Prefix = 'C:\Program Files\PostgreSQL\14',
    [string]$DataDir = 'C:\Program Files\PostgreSQL\14\data',
    [string]$ServiceName = 'postgresql-x64-14'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$installerUrl = 'https://get.enterprisedb.com/postgresql/postgresql-14.12-1-windows-x64.exe'
$installerPath = Join-Path $env:TEMP 'postgresql-14-installer.exe'

Write-Output "Downloading PostgreSQL 14 installer..."
Invoke-WebRequest -Uri $installerUrl -OutFile $installerPath

Write-Output "Running silent install..."
$arguments = @(
    '--mode','unattended',
    '--unattendedmodeui','minimal',
    '--superpassword', $SuperPassword,
    '--serverport', $Port,
    '--servicename', $ServiceName,
    '--servicepassword', $SuperPassword,
    '--prefix', $Prefix,
    '--datadir', $DataDir,
    '--components','postgresql,pgAdmin'
)

Start-Process -FilePath $installerPath -ArgumentList $arguments -Wait

# Append bin to user PATH
$binPath = Join-Path $Prefix 'bin'
$currentPath = [Environment]::GetEnvironmentVariable('Path','User')
if ($currentPath -notlike "*$binPath*") {
    $newPath = if ([string]::IsNullOrEmpty($currentPath)) { $binPath } else { "$currentPath;$binPath" }
    [Environment]::SetEnvironmentVariable('Path',$newPath,'User')
}

# Set PG environment hint
[Environment]::SetEnvironmentVariable('PGHOME',$Prefix,'User')

# Verify
$psql = Join-Path $binPath 'psql.exe'
if (Test-Path $psql) {
    Write-Output "psql located at $psql"
    & $psql --version
} else {
    Write-Warning "psql.exe not found at expected path: $psql"
}

Write-Output "PostgreSQL 14 install script finished. Restart terminal to pick up PATH changes."
