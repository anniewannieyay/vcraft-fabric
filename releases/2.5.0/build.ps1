# Builds VCraft 2.5.0 for Fabric from the 2.4.0 jar: compiles the sources in
# src/ (core and api against both games, then each game's mc/ package against
# that game) and swaps the classes into a copy of the jar. Every other entry
# is copied byte for byte.
#
#   powershell -File build.ps1            # build dist/vcraft-2.5.0.jar
#   powershell -File build.ps1 -Check     # compile only
param([switch]$Check)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$jdk = "$env:USERPROFILE\.jdks\jdk-25.0.4.1+1\bin"
$base = "$here\..\..\dist\vcraft-2.4.0.jar"
$out = "$here\..\..\dist\vcraft-2.5.0.jar"
$prism = "$env:APPDATA\PrismLauncher\libraries"

# Libraries the game ships (gson, lwjgl, brigadier, netty...), any version.
# Only the newest version of each library (javac takes the first class it finds).
$libs = Get-ChildItem $prism -Recurse -Filter *.jar | Where-Object {
    $_.FullName -notmatch '\\com\\mojang\\minecraft\\|\\net\\fabricmc\\intermediary\\|\\net\\fabricmc\\fabric-loader\\'
} | Group-Object { $_.Directory.Parent.FullName } | ForEach-Object {
    $_.Group | Sort-Object { $v = $_.Directory.Name -replace '[^0-9.].*$', ''; try { [version]($v + ('.0' * [Math]::Max(0, 2 - ($v.Split('.').Count - 1)))) } catch { [version]'0.0' } } -Descending | Select-Object -First 1
} | ForEach-Object FullName
# The exact libraries a game version uses, from Prism's metadata (first on the classpath).
function MetaLibs($version) {
    $meta = "$env:APPDATA\PrismLauncher\meta\net.minecraft\$version.json"
    if (-not (Test-Path $meta)) { return @() }
    (Get-Content $meta -Raw | ConvertFrom-Json).libraries | ForEach-Object {
        $p = $_.name.Split(':')
        $file = "$prism\$($p[0].Replace('.', '\'))\$($p[1])\$($p[2])\$($p[1])-$($p[2])$(if ($p.Count -gt 3) { '-' + $p[3] }).jar"
        if (Test-Path $file) { $file }
    }
}
$loader =(Get-ChildItem "$prism\net\fabricmc\fabric-loader" -Recurse -Filter "fabric-loader-0.19.3.jar" | Select-Object -First 1).FullName
$mc26 = "$prism\com\mojang\minecraft\26.3\minecraft-26.3-client.jar"
$mc121 = (Get-ChildItem "$env:APPDATA\PrismLauncher\instances\Endsacape\minecraft\.fabric\remappedJars" -Recurse -Filter client-intermediary.jar | Select-Object -First 1).FullName
$api26 = Get-ChildItem "$here\lib\api26" -Filter *.jar | ForEach-Object FullName
$api121 = Get-ChildItem "$here\lib\api121" -Filter *.jar | ForEach-Object FullName

$classes = "$here\classes"
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory $classes | Out-Null

function Compile($name, $sources, $cp) {
    if (-not $sources) { return }
    $argfile = "$here\$name.args"
    $lines = @('--release', '21', '-encoding', 'UTF-8', '-nowarn', '-proc:none', '-d', "`"$($classes -replace '\\','/')`"",
        '-cp', "`"$((@($classes) + $cp) -join ';' -replace '\\','/')`"") + ($sources | ForEach-Object { "`"$($_ -replace '\\','/')`"" })
    Set-Content $argfile $lines -Encoding ascii
    Write-Host "compiling $name ($($sources.Count) files)"
    & "$jdk\javac.exe" "@$argfile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $name" }
}

$src = "$here\src"
$core = Get-ChildItem "$src\dev\vcraft\core", "$src\dev\vcraft\api" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName
$v26 = Get-ChildItem "$src\dev\vcraft\mc\v26_3" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName
$v121 = Get-ChildItem "$src\dev\vcraft\mc\v1_21_11" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName

# Core only uses Java and gson; compile it once, against the base jar.
Compile 'core' $core (@($base, $loader) + $libs)
Compile 'v26_3' $v26 (@($base, $loader, $mc26) + $api26 + (MetaLibs '26.3') + $libs)
Compile 'v1_21_11' $v121 (@($base, $loader, $mc121) + $api121 + (MetaLibs '1.21.11') + $libs)
Remove-Item "$here\*.args"

if ($Check) { Write-Host "compiled OK"; return }
& python "$here\repack25.py" $base $classes $out
if ($LASTEXITCODE -ne 0) { throw "repack failed" }
