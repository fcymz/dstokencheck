<#
    DeepSeek 余额小窗 —— uninstaller.

    Normally started by the Apps & Features entry, which passes -Dir so there is no guessing about
    which copy is being removed. Also runnable by hand from the install folder.

        -Dir <path>  the installation to remove (default: look it up, then use this script's folder)
        -Silent      no dialogs, keep the settings
        -PurgeData   also delete %USERPROFILE%\.dstokencheck (settings, background images, presets)
#>
param(
    [string]$Dir = "",
    [switch]$Silent,
    [switch]$PurgeData,
    # Empty means the standard Windows locations; the install test points them at a scratch folder.
    [string]$StartMenuDir = "",
    [string]$DesktopDir = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$AppName = "DeepSeek 余额小窗"

function Find-InstallDir {
    foreach ($root in @("HKLM:", "HKCU:")) {
        $base = "$root\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"
        if (-not (Test-Path $base)) { continue }
        foreach ($item in (Get-ChildItem $base -ErrorAction SilentlyContinue)) {
            $props = Get-ItemProperty $item.PSPath -ErrorAction SilentlyContinue
            if (-not $props -or -not $props.InstallLocation) { continue }
            if ($props.DisplayName -notlike "$AppName*") { continue }
            $candidate = $props.InstallLocation.TrimEnd('\')
            if (Test-Path (Join-Path $candidate "dstokencheck.jar")) { return $candidate }
        }
    }
    return (Split-Path -Parent $PSCommandPath)
}

if ([string]::IsNullOrWhiteSpace($Dir)) { $Dir = Find-InstallDir }
$Dir = [System.IO.Path]::GetFullPath($Dir)
$ConfigDir = Join-Path $env:USERPROFILE ".dstokencheck"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
$RunValue = "dstokencheck"

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not $Silent) {
    [System.Windows.Forms.Application]::EnableVisualStyles()
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "确定要卸载 $AppName 吗？`n`n程序位置：$Dir`n`n（你保存的设置和背景图会保留）",
        "$AppName 卸载程序",
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($answer -ne [System.Windows.Forms.DialogResult]::Yes) { exit 0 }

    $purge = [System.Windows.Forms.MessageBox]::Show(
        "是否同时删除保存在 $ConfigDir 里的设置、背景图和预设？`n`n选「否」以后重装还能用回原来的样子。",
        "$AppName 卸载程序",
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Question)
    $PurgeData = ($purge -eq [System.Windows.Forms.DialogResult]::Yes)
}

# Program Files needs an administrator; a per-user install does not, and there the uninstall entry
# lives in HKCU anyway.
$writable = $false
try {
    $probe = New-Object System.IO.FileInfo (Join-Path $Dir ".write-test")
    $stream = [System.IO.File]::Create($probe.FullName)
    $stream.Close()
    Remove-Item $probe.FullName -Force -ErrorAction SilentlyContinue
    $writable = $true
} catch { $writable = $false }

if (-not $writable -and -not (Test-Admin)) {
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"",
                 "-Dir", "`"$Dir`"")
    if ($Silent) { $argList += "-Silent" }
    if ($PurgeData) { $argList += "-PurgeData" }
    try {
        $p = Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $argList -PassThru -Wait
        exit $p.ExitCode
    } catch {
        if (-not $Silent) {
            [void][System.Windows.Forms.MessageBox]::Show("需要管理员权限才能完成卸载。", "$AppName 卸载程序",
                [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
        }
        exit 1
    }
}

# Stop the widget if it is running from this folder.
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*$Dir*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# Shortcuts. Both the all-users and the per-user locations are searched: which one was used
# depends on whether the install was elevated.
$shell = New-Object -ComObject WScript.Shell
$startMenuDirs = if (-not [string]::IsNullOrWhiteSpace($StartMenuDir)) {
    @($StartMenuDir)
} else {
    @(
        (Join-Path ([Environment]::GetFolderPath("CommonStartMenu")) "Programs"),
        (Join-Path ([Environment]::GetFolderPath("StartMenu")) "Programs")
    )
}
foreach ($startMenuDir in $startMenuDirs) {
    if (-not $startMenuDir -or -not (Test-Path $startMenuDir)) { continue }
    Get-ChildItem $startMenuDir -Filter "*.lnk" -ErrorAction SilentlyContinue | Where-Object {
        try { $shell.CreateShortcut($_.FullName).TargetPath -like "$Dir*" } catch { $false }
    } | ForEach-Object { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
}

$desktopDirs = if (-not [string]::IsNullOrWhiteSpace($DesktopDir)) {
    @($DesktopDir)
} else {
    @(
        [Environment]::GetFolderPath("CommonDesktopDirectory"),
        [Environment]::GetFolderPath("Desktop")
    )
}
foreach ($desktopDir in $desktopDirs) {
    if (-not $desktopDir -or -not (Test-Path $desktopDir)) { continue }
    Get-ChildItem $desktopDir -Filter "*.lnk" -ErrorAction SilentlyContinue | Where-Object {
        try { $shell.CreateShortcut($_.FullName).TargetPath -like "$Dir*" } catch { $false }
    } | ForEach-Object { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
}

# Uninstall entry, whichever root it was written to.
foreach ($root in @("HKLM:", "HKCU:")) {
    Get-ChildItem "$root\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall" -ErrorAction SilentlyContinue |
        Where-Object {
            $loc = (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).InstallLocation
            $loc -and ($loc.TrimEnd('\') -ieq $Dir.TrimEnd('\'))
        } | ForEach-Object { Remove-Item $_.PSPath -Recurse -Force -ErrorAction SilentlyContinue }
}

# Auto-start, if the app registered it, plus the copy it keeps for that purpose.
$runValue = (Get-ItemProperty -Path $RunKey -Name $RunValue -ErrorAction SilentlyContinue).$RunValue
if ($runValue) {
    $stable = Join-Path $env:LOCALAPPDATA "dstokencheck"
    if ($runValue -like "*$Dir*" -or $runValue -like "*$stable*") {
        Remove-ItemProperty -Path $RunKey -Name $RunValue -Force -ErrorAction SilentlyContinue
    }
}
Remove-Item -Recurse -Force (Join-Path $env:LOCALAPPDATA "dstokencheck") -ErrorAction SilentlyContinue

if ($PurgeData) { Remove-Item -Recurse -Force $ConfigDir -ErrorAction SilentlyContinue }

# Delete the folder. This script lives in it, so the last step is handed to a detached shell that
# runs once this process is gone.
$cleanup = "ping -n 2 127.0.0.1 >nul & rmdir /s /q `"$Dir`""
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cleanup -WindowStyle Hidden | Out-Null

if (-not $Silent) {
    [void][System.Windows.Forms.MessageBox]::Show("$AppName 已卸载。", "$AppName 卸载程序",
        [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information)
}
exit 0
