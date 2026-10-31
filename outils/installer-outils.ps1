# Installe les outils du projet dans .outils (rien n'est installé ailleurs sur le PC) :
# Java 21 (Temurin) pour compiler le mod, Go + packwiz pour gérer le pack.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$racine = Split-Path -Parent $PSScriptRoot
$outils = Join-Path $racine ".outils"
New-Item -ItemType Directory -Force $outils | Out-Null

if (-not (Test-Path "$outils\jdk21")) {
    Write-Host "Téléchargement de Java 21..."
    Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile "$outils\jdk21.zip"
    Expand-Archive "$outils\jdk21.zip" "$outils\jdk21"; Remove-Item "$outils\jdk21.zip"
}
if (-not (Test-Path "$outils\go-dist")) {
    Write-Host "Téléchargement de Go..."
    $version = (Invoke-WebRequest "https://go.dev/VERSION?m=text" -UseBasicParsing).Content.Split("`n")[0]
    Invoke-WebRequest "https://go.dev/dl/$version.windows-amd64.zip" -OutFile "$outils\go.zip"
    Expand-Archive "$outils\go.zip" "$outils\go-dist"; Remove-Item "$outils\go.zip"
}
if (-not (Test-Path "$outils\gopath\bin\packwiz.exe")) {
    Write-Host "Compilation de packwiz..."
    $env:GOROOT = "$outils\go-dist\go"; $env:GOPATH = "$outils\gopath"; $env:GOCACHE = "$outils\gocache"; $env:GOTELEMETRY = "off"
    & "$outils\go-dist\go\bin\go.exe" install github.com/packwiz/packwiz@latest
}
Write-Host "Outils prêts dans $outils"
