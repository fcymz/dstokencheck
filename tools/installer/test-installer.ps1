<#
    End-to-end test of the built setup package.

    Drives the real .exe (through the DSTOKENCHECK_SETUP_ARGS hook install.cmd understands), then
    checks what a user would see: the files, the shortcut, the Apps & Features entry, the bundled
    runtime actually running the app, and finally that the uninstaller cleans it all up again.

    Everything lands in a throwaway directory, a throwaway shortcut name and a throwaway registry
    key, so running this does not disturb an installed copy.
#>
param(
    [string]$Version = "1.1.5",
    [string]$TestDir = "",
    [string]$ShortcutName = "DeepSeek 余额小窗 (安装测试)",
    [string]$RegistryKey = "dstokencheck-installtest"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path (Join-Path $root "pom.xml"))) { $root = (Get-Location).Path }
if ([string]::IsNullOrWhiteSpace($TestDir)) { $TestDir = Join-Path $root "build\install-test" }

$setup = Join-Path $root "dist\dstokencheck-$Version-setup.exe"
if (-not (Test-Path $setup)) { throw "missing $setup" }

$results = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail = "") {
    [void]$results.Add($ok)
    "{0} {1}{2}" -f $(if ($ok) { "OK  " } else { "FAIL" }), $name, $(if ($detail) { "  ($detail)" } else { "" })
}

function Get-Shortcut([string]$path) {
    $shell = New-Object -ComObject WScript.Shell
    return $shell.CreateShortcut($path)
}

# ---------------------------------------------------------------- clean slate
Remove-Item -Recurse -Force $TestDir -ErrorAction SilentlyContinue
$shortcutRoot = Join-Path $root "build\install-test-shortcuts"
$testLink = Join-Path $shortcutRoot "$ShortcutName.lnk"
Remove-Item $testLink -Force -ErrorAction SilentlyContinue
$testKey = "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$RegistryKey"
Remove-Item $testKey -Recurse -Force -ErrorAction SilentlyContinue

"==> running the setup package silently into $TestDir"
$sandboxTemp = Join-Path $root "build\install-test-tmp"
New-Item -ItemType Directory -Force -Path $sandboxTemp | Out-Null
$savedTemp = $env:TEMP; $savedTmp = $env:TMP
$env:TEMP = $sandboxTemp; $env:TMP = $sandboxTemp
$shortcutRoot = Join-Path $root "build\install-test-shortcuts"
Remove-Item -Recurse -Force $shortcutRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $shortcutRoot | Out-Null
# Quoted by hand: Start-Process joins -ArgumentList with spaces and does not quote for us.
$setupArgs = @("-InstallDir", $TestDir, "-Silent", "-NoLaunch", "-NoDesktopShortcut",
               "-StartMenuName", "`"$ShortcutName`"", "-RegistryKey", $RegistryKey,
               "-StartMenuDir", "`"$shortcutRoot`"", "-DesktopDir", "`"$shortcutRoot`"")
Remove-Item (Join-Path $sandboxTemp "dstokencheck-setup*.log") -Force -ErrorAction SilentlyContinue
$p = Start-Process -FilePath $setup -ArgumentList $setupArgs -PassThru
$deadline = (Get-Date).AddMinutes(6)
while (-not $p.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
"    setup exited: $($p.HasExited)  code: $($p.ExitCode)"
if ($p.ExitCode -ne 0) {
    "    --- installer log ---"
    Get-Content (Join-Path $sandboxTemp "dstokencheck-setup.log") -ErrorAction SilentlyContinue | ForEach-Object { "      $_" }
}

# ---------------------------------------------------------------- what got installed
Check "程序文件 dstokencheck.jar" (Test-Path (Join-Path $TestDir "dstokencheck.jar"))
Check "自带运行时 runtime\bin\javaw.exe" (Test-Path (Join-Path $TestDir "runtime\bin\javaw.exe"))
Check "卸载脚本 uninstall.ps1" (Test-Path (Join-Path $TestDir "uninstall.ps1"))
Check "图标 app.ico" (Test-Path (Join-Path $TestDir "app.ico"))
$installedSize = (Get-ChildItem $TestDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
"     installed size: $([math]::Round($installedSize,1)) MB"

if (Test-Path $testLink) {
    $lnk = Get-Shortcut $testLink
    Check "开始菜单快捷方式指向自带的 javaw.exe" ($lnk.TargetPath -ieq (Join-Path $TestDir "runtime\bin\javaw.exe")) $lnk.TargetPath
    Check "快捷方式参数是自己那份 jar" ($lnk.Arguments -like "*$(Join-Path $TestDir 'dstokencheck.jar')*") $lnk.Arguments
    Check "快捷方式用上了自定义图标" ($lnk.IconLocation -like "*app.ico*") $lnk.IconLocation
} else {
    Check "开始菜单快捷方式" $false $testLink
}

if (Test-Path $testKey) {
    $props = Get-ItemProperty $testKey
    Check "卸载信息有名称和版本" ($props.DisplayName -and $props.DisplayVersion -eq $Version) "$($props.DisplayName) $($props.DisplayVersion)"
    Check "卸载信息记录安装位置" ($props.InstallLocation.TrimEnd('\') -ieq $TestDir.TrimEnd('\')) $props.InstallLocation
    Check "卸载命令带上了安装路径" ($props.UninstallString -like "*uninstall.ps1*" -and $props.UninstallString -like "*$TestDir*") $props.UninstallString
    Check "体积已登记（设置→应用里会显示）" ($props.EstimatedSize -gt 1000) "$($props.EstimatedSize) KB"
} else {
    Check "注册表卸载项" $false $testKey
}

# ---------------------------------------------------------------- does the installed copy run?
$bundledJava = Join-Path $TestDir "runtime\bin\java.exe"
$bundledJar = Join-Path $TestDir "dstokencheck.jar"
$appHome = Join-Path $root "build\install-test-home"
$tmp = Join-Path $root "build\install-test-tmp"
Remove-Item -Recurse -Force $appHome, $tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$out = & $bundledJava "-Dfile.encoding=UTF-8" "-Duser.home=$appHome" "-Djava.io.tmpdir=$tmp" -jar $bundledJar --selftest 2>&1
$pass = ($out | Select-String '^PASS').Count
$fail = ($out | Select-String '^FAIL').Count
Check "装完之后直接用自带运行时能跑（自测 $pass 项）" ($LASTEXITCODE -eq 0 -and $fail -eq 0)
$out | Select-String '^FAIL' | ForEach-Object { "        $($_.Line.Trim())" }

# ---------------------------------------------------------------- uninstall
"==> running the installed uninstaller silently"
$u = Start-Process -FilePath "powershell.exe" -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
    "-File", (Join-Path $TestDir "uninstall.ps1"), "-Dir", $TestDir, "-Silent",
    "-StartMenuDir", $shortcutRoot, "-DesktopDir", $shortcutRoot) -PassThru
$deadline = (Get-Date).AddMinutes(4)
while (-not $u.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
Start-Sleep -Seconds 6   # the last step is a detached cleanup of its own folder

Check "程序目录已删除" (-not (Test-Path $TestDir)) $TestDir
Check "开始菜单快捷方式已删除" (-not (Test-Path $testLink))
Check "注册表卸载项已删除" (-not (Test-Path $testKey))

$env:TEMP = $savedTemp; $env:TMP = $savedTmp

# ---------------------------------------------------------------- verdict
$failed = ($results | Where-Object { -not $_ }).Count
""
"$($results.Count - $failed)/$($results.Count) checks passed"
if ($failed -gt 0) { exit 1 }
