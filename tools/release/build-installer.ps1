# Builds the Windows setup FarmPulse-<v>-Setup.exe (docs/architecture/windows-installer.md).
#
#   tools/release/build-installer.ps1 -Version 1.7.0 [-SetupName FarmPulse-1.7.0-snapshot-Setup]
#
# Runs on Windows with JDK 21 (jpackage) and Inno Setup 6.3+ (installed via Chocolatey when missing). Input is the output
# of tools/release/build-release.sh in release/: rpsim-backend-<v>.jar, farmpulse-web-<v>.zip, FS25_RPSim.zip.
# Output: release/<SetupName>.exe (default FarmPulse-<v>-Setup.exe).
param(
    [Parameter(Mandatory = $true)] [string] $Version,
    [string] $SetupName = "FarmPulse-$Version-Setup"
)
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$Release = Join-Path $Root 'release'
$Work = Join-Path $Release 'installer-work'
$AppInput = Join-Path $Work 'input'
$Image = Join-Path $Work 'image'
$Icon = Join-Path $Root 'frontend/public/favicon.ico'

function Need([string] $Path) {
    if (-not (Test-Path $Path)) { throw "missing: $Path (run tools/release/build-release.sh first)" }
}
Need (Join-Path $Release "rpsim-backend-$Version.jar")
Need (Join-Path $Release "farmpulse-web-$Version.zip")
Need (Join-Path $Release 'FS25_RPSim.zip')
Need $Icon

Write-Host "==> FarmPulse $Version setup ($SetupName.exe)"
if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
New-Item -ItemType Directory -Force (Join-Path $AppInput 'web') | Out-Null
Copy-Item (Join-Path $Release "rpsim-backend-$Version.jar") (Join-Path $AppInput 'rpsim-backend.jar')
Expand-Archive (Join-Path $Release "farmpulse-web-$Version.zip") (Join-Path $AppInput 'web')
Need (Join-Path $AppInput 'web/index.html')

# ---------------------------------------------------------------- app image: FarmPulse.exe + own Java runtime
# No --win-console: the launcher starts without a console window. The backend finds web\ next to its jar and switches to
# the desktop mode (profiles prod,desktop, tray icon, browser) through rpsim.desktop.enabled.
Write-Host '==> jpackage (app image)'
& jpackage --type app-image `
    --name FarmPulse `
    --app-version $Version `
    --vendor 'FarmPulse (Fan-Projekt)' `
    --description 'FarmPulse - KI-Rollenspiel fuer Farming Simulator 25' `
    --icon $Icon `
    --input $AppInput `
    --main-jar rpsim-backend.jar `
    --java-options '-Drpsim.desktop.enabled=true' `
    --dest $Image
if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }
Need (Join-Path $Image 'FarmPulse/FarmPulse.exe')
Need (Join-Path $Image 'FarmPulse/app/web/index.html')

# ---------------------------------------------------------------- Inno Setup
$Iscc = $null
$Command = Get-Command 'iscc.exe' -ErrorAction SilentlyContinue
if ($Command) { $Iscc = $Command.Source }
foreach ($Candidate in @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe")) {
    if (-not $Iscc -and (Test-Path $Candidate)) { $Iscc = $Candidate }
}
if (-not $Iscc) {
    Write-Host '==> installing Inno Setup (Chocolatey)'
    & choco install innosetup --yes --no-progress
    if ($LASTEXITCODE -ne 0) { throw "choco install innosetup failed ($LASTEXITCODE)" }
    $Iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
    Need $Iscc
}

Write-Host "==> Inno Setup ($Iscc)"
& $Iscc /Qp `
    "/DAppVersion=$Version" `
    "/DSourceImage=$(Join-Path $Image 'FarmPulse')" `
    "/DModZip=$(Join-Path $Release 'FS25_RPSim.zip')" `
    "/DIconFile=$Icon" `
    "/DOutputDir=$Release" `
    "/DOutputBaseName=$SetupName" `
    (Join-Path $PSScriptRoot 'installer/FarmPulse.iss')
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed ($LASTEXITCODE)" }
Need (Join-Path $Release "$SetupName.exe")

Remove-Item -Recurse -Force $Work
Write-Host '==> done'
Get-Item (Join-Path $Release "$SetupName.exe") | Format-Table Name, Length
