<#
    Builds the Windows installer for DeepSeek 余额小窗.

    Everything here uses tools that ship with Windows: javac/jar from the JDK for the app, and the
    C# compiler (csc.exe) to build the setup .exe that carries the payload. No NSIS, no Inno Setup,
    no IExpress, nothing to install first.

    Output:
        dist\dstokencheck-<version>-setup.exe     the one-click installer
#>
param(
    [string]$Version = "1.1.5",
    [string]$Jdk = "C:\Program Files\Java\jdk1.8.0_181",
    [string]$Runtime = "",
    [string]$JarPath = "",
    [switch]$SkipJar
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path (Join-Path $root "pom.xml"))) { $root = (Get-Location).Path }
$here = Join-Path $root "build\installer"
$payloadDir = Join-Path $here "payload"
$dist = Join-Path $root "dist"
if ([string]::IsNullOrWhiteSpace($Runtime)) { $Runtime = Join-Path $root "build\runtime8" }

function Step([string]$text) { Write-Output "==> $text" }

# ---------------------------------------------------------------- 1. the app jar

$jar = if ([string]::IsNullOrWhiteSpace($JarPath)) { Join-Path $root "target\dstokencheck.jar" } else { $JarPath }
if (-not $SkipJar -and [string]::IsNullOrWhiteSpace($JarPath)) {
    Step "Compiling and packaging the app jar"
    $classes = Join-Path $root "build\app"
    Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    Copy-Item -Recurse -Force (Join-Path $root "src\main\resources\*") $classes
    $sources = Get-ChildItem -Recurse (Join-Path $root "src\main\java") -Filter *.java | ForEach-Object { $_.FullName }
    & (Join-Path $Jdk "bin\javac.exe") -encoding UTF-8 -source 1.8 -target 1.8 -nowarn `
        -cp $jar -d $classes $sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }
    $work = Join-Path $root "build\jarwork"
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    Push-Location $work; & jar xf (Join-Path $root "build\old.jar"); Pop-Location
    Remove-Item -Recurse -Force (Join-Path $work "com\ruoyi"), (Join-Path $work "presets") -ErrorAction SilentlyContinue
    Get-ChildItem $work -Filter *.class | Remove-Item -Force
    Copy-Item -Recurse -Force (Join-Path $classes "*") $work
    & jar cfe $jar com.ruoyi.dstokencheck.Main -C $work .
    if ($LASTEXITCODE -ne 0) { throw "jar failed" }
}
if (-not (Test-Path $jar)) { throw "missing $jar" }
"    jar: $([math]::Round((Get-Item $jar).Length/1KB)) KB"

# ---------------------------------------------------------------- 3. payload

Step "Assembling the payload"
Remove-Item -Recurse -Force $payloadDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $payloadDir | Out-Null
Copy-Item $jar (Join-Path $payloadDir "dstokencheck.jar")
Copy-Item (Join-Path $here "app.ico") $payloadDir
Copy-Item (Join-Path $here "uninstall.ps1") $payloadDir
Copy-Item -Recurse -Force $Runtime (Join-Path $payloadDir "runtime")
if (-not (Test-Path (Join-Path $payloadDir "runtime\bin\javaw.exe"))) { throw "the bundled runtime has no javaw.exe" }

$payloadZip = Join-Path $here "payload.zip"
Remove-Item -Force $payloadZip -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $payloadDir, $payloadZip, [System.IO.Compression.CompressionLevel]::Fastest, $false)
"    payload.zip: $([math]::Round((Get-Item $payloadZip).Length/1MB,2)) MB"

# ---------------------------------------------------------------- 4. the setup package

# IExpress was tried first (it needs nothing installed) and abandoned: on this machine its stub
# sat on the extraction step for a 46 MB payload and never started. The C# compiler that ships
# with Windows builds a better setup .exe anyway — the payload rides inside it as a resource, it
# has no console window, and it can carry the app icon.
Step "Compiling the setup bootstrapper (csc)"
$csc = ""
foreach ($candidate in @(
        "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
        "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe")) {
    if (Test-Path $candidate) { $csc = $candidate; break }
}
if ([string]::IsNullOrWhiteSpace($csc)) { throw "no C# compiler on this machine (csc.exe not found)" }
"    compiler: $csc"

$setupStage = Join-Path $here "setup-stage"
Remove-Item -Recurse -Force $setupStage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $setupStage | Out-Null
Copy-Item (Join-Path $here "install.ps1") $setupStage
Copy-Item (Join-Path $here "install.cmd") $setupStage
Copy-Item $payloadZip $setupStage
Copy-Item (Join-Path $here "app.ico") $setupStage

New-Item -ItemType Directory -Force -Path $dist | Out-Null
$setupExe = Join-Path $dist "dstokencheck-$Version-setup.exe"
Remove-Item -Force $setupExe -ErrorAction SilentlyContinue

$cscArgs = New-Object System.Collections.ArrayList
foreach ($arg in @(
        "/nologo",
        "/target:winexe",
        "/optimize+",
        "/out:$setupExe",
        ("/win32icon:" + (Join-Path $here "app.ico")),
        ("/resource:" + (Join-Path $setupStage "payload.zip") + ",payload.zip"),
        ("/resource:" + (Join-Path $setupStage "install.ps1") + ",install.ps1"),
        ("/resource:" + (Join-Path $setupStage "app.ico") + ",app.ico"),
        (Join-Path $here "setup.cs"))) {
    [void]$cscArgs.Add($arg)
}
& $csc $cscArgs
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $setupExe)) { throw "csc failed" }

$size = (Get-Item $setupExe).Length
$hash = (Get-FileHash $setupExe -Algorithm SHA256).Hash
""
"Setup package : $setupExe"
"Size          : $([math]::Round($size/1MB,2)) MB"
"SHA256        : $hash"