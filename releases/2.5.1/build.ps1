# Builds VCraft 2.5.1 for Fabric from the 2.5.0 jar: compiles RelayConnection and
# VCraft against it and swaps the classes into a copy (see ..\README.md).
#
#   powershell -File build.ps1 -Base path\to\vcraft-2.5.0.jar [-Out vcraft-2.5.1.jar]
param([Parameter(Mandatory)][string]$Base, [string]$Out = "vcraft-2.5.1.jar", [string]$Jdk = "")
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $Jdk) { $Jdk = Split-Path (Get-Command javac).Source }
$libs = "$env:APPDATA\PrismLauncher\libraries"
$gson = Get-ChildItem "$libs\com\google\code\gson\gson" -Recurse -Filter "gson-*.jar" | Sort-Object FullName | Select-Object -Last 1
$slf4j = Get-ChildItem "$libs\org\slf4j\slf4j-api" -Recurse -Filter "slf4j-api-*.jar" | Sort-Object FullName | Select-Object -Last 1
$classes = Join-Path $here "classes"
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory $classes | Out-Null
& "$Jdk\javac.exe" --release 21 -encoding UTF-8 -proc:none -d $classes -cp "$Base;$($gson.FullName);$($slf4j.FullName)" `
    "$here\src\dev\vcraft\core\RelayConnection.java" "$here\src\dev\vcraft\core\VCraft.java"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
& "$Jdk\java.exe" "$here\Repack.java" $Base $classes $Out
if ($LASTEXITCODE -ne 0) { throw "repack failed" }
