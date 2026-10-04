<#
    DeepSeek 余额小窗 —— installer.

    Runs from the folder the setup package was extracted into, which holds:
        payload.zip     the app jar + the bundled Java runtime + the uninstaller
        app.ico         the shortcut icon
        install.ps1     this script

    Interactive by default (asks where to install, remembers no state); every question can be
    answered from the command line instead, which is how the package is tested:

        -InstallDir <path>       install here (default: %ProgramFiles%\dstokencheck)
        -Silent                  no dialogs; use the defaults / the arguments given
        -NoLaunch                do not start the app when done
        -NoDesktopShortcut       skip the desktop shortcut
        -StartMenuName <name>    shortcut name
        -RegistryKey <key>       uninstall registry key (tests use a throwaway one)
#>
param(
    [string]$InstallDir = "",
    [string]$PayloadDir = "",
    [switch]$Silent,
    [switch]$NoLaunch,
    [switch]$NoDesktopShortcut,
    [string]$StartMenuName = "DeepSeek 余额小窗",
    [string]$RegistryKey = "dstokencheck",
    [string]$DisplayVersion = "1.1.5",
    # Empty means "wherever Windows puts them"; set only by the install test, which has to keep
    # its footprints inside a scratch folder.
    [string]$StartMenuDir = "",
    [string]$DesktopDir = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$AppName = "DeepSeek 余额小窗"
$ExeName = "dstokencheck.jar"
$Source = if ([string]::IsNullOrWhiteSpace($PayloadDir)) { $PSScriptRoot } else { $PayloadDir }
$Payload = Join-Path $Source "payload.zip"
$Icon = Join-Path $Source "app.ico"
$DefaultDir = Join-Path $env:ProgramFiles "dstokencheck"
$LogFile = Join-Path ([System.IO.Path]::GetTempPath()) "dstokencheck-setup.log"

# A winexe launcher has nowhere to print, so the installer keeps its own log: that is what makes a
# failed install diagnosable after the fact.
function Write-Log([string]$text) {
    try {
        Add-Content -Path $LogFile -Value ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $text) -ErrorAction SilentlyContinue
    } catch { }
}

# When the compiled launcher starts us it passes a file to append everything to.
$childLog = $env:DSTOKENCHECK_SETUP_CHILD_LOG
if (-not [string]::IsNullOrWhiteSpace($childLog)) {
    try { Start-Transcript -Path $childLog -Force | Out-Null } catch { }
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Show-Error([string]$message) {
    if (-not $Silent) {
        [void][System.Windows.Forms.MessageBox]::Show($message, "$AppName 安装程序",
            [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
    } else {
        Write-Error $message
    }
}

function Show-Info([string]$message) {
    if (-not $Silent) {
        [void][System.Windows.Forms.MessageBox]::Show($message, "$AppName 安装程序",
            [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information)
    } else {
        Write-Output $message
    }
}

# --------------------------------------------------------------- 1. where to install

$desktop = -not $NoDesktopShortcut
$launch = -not $NoLaunch

if (-not $Silent -and [string]::IsNullOrWhiteSpace($InstallDir)) {
    $form = New-Object System.Windows.Forms.Form
    $form.Text = "$AppName 安装程序"
    $form.Size = New-Object System.Drawing.Size(560, 300)
    $form.StartPosition = "CenterScreen"
    $form.FormBorderStyle = "FixedDialog"
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 9)
    if (Test-Path $Icon) { $form.Icon = New-Object System.Drawing.Icon($Icon) }

    $title = New-Object System.Windows.Forms.Label
    $title.Text = "把 $AppName 安装到这台电脑"
    $title.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 12, [System.Drawing.FontStyle]::Bold)
    $title.Location = New-Object System.Drawing.Point(24, 20)
    $title.Size = New-Object System.Drawing.Size(500, 30)
    $form.Controls.Add($title)

    $hint = New-Object System.Windows.Forms.Label
    $hint.Text = "程序自带 Java 运行环境，装完即可使用，不需要另外安装 Java。"
    $hint.ForeColor = [System.Drawing.Color]::FromArgb(90, 90, 90)
    $hint.Location = New-Object System.Drawing.Point(26, 56)
    $hint.Size = New-Object System.Drawing.Size(500, 22)
    $form.Controls.Add($hint)

    $label = New-Object System.Windows.Forms.Label
    $label.Text = "安装位置："
    $label.Location = New-Object System.Drawing.Point(26, 96)
    $label.Size = New-Object System.Drawing.Size(80, 22)
    $form.Controls.Add($label)

    $dirBox = New-Object System.Windows.Forms.TextBox
    $dirBox.Text = $DefaultDir
    $dirBox.Location = New-Object System.Drawing.Point(106, 93)
    $dirBox.Size = New-Object System.Drawing.Size(336, 24)
    $form.Controls.Add($dirBox)

    $browse = New-Object System.Windows.Forms.Button
    $browse.Text = "浏览..."
    $browse.Location = New-Object System.Drawing.Point(450, 92)
    $browse.Size = New-Object System.Drawing.Size(80, 26)
    $browse.Add_Click({
        $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
        $dialog.Description = "选择安装位置"
        if (Test-Path $dirBox.Text) { $dialog.SelectedPath = $dirBox.Text }
        if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
            # Installing straight into the chosen folder keeps the uninstaller's job simple.
            $dirBox.Text = Join-Path $dialog.SelectedPath "dstokencheck"
        }
    })
    $form.Controls.Add($browse)

    $deskBox = New-Object System.Windows.Forms.CheckBox
    $deskBox.Text = "创建桌面快捷方式"
    $deskBox.Checked = $true
    $deskBox.Location = New-Object System.Drawing.Point(106, 132)
    $deskBox.Size = New-Object System.Drawing.Size(220, 24)
    $form.Controls.Add($deskBox)

    $launchBox = New-Object System.Windows.Forms.CheckBox
    $launchBox.Text = "安装完成后立即启动"
    $launchBox.Checked = $true
    $launchBox.Location = New-Object System.Drawing.Point(106, 160)
    $launchBox.Size = New-Object System.Drawing.Size(220, 24)
    $form.Controls.Add($launchBox)

    $ok = New-Object System.Windows.Forms.Button
    $ok.Text = "安装"
    $ok.Location = New-Object System.Drawing.Point(346, 205)
    $ok.Size = New-Object System.Drawing.Size(90, 30)
    $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $form.AcceptButton = $ok
    $form.Controls.Add($ok)

    $cancel = New-Object System.Windows.Forms.Button
    $cancel.Text = "取消"
    $cancel.Location = New-Object System.Drawing.Point(442, 205)
    $cancel.Size = New-Object System.Drawing.Size(90, 30)
    $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
    $form.CancelButton = $cancel
    $form.Controls.Add($cancel)

    if ($form.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit 0 }
    $InstallDir = $dirBox.Text.Trim()
    $desktop = $deskBox.Checked
    $launch = $launchBox.Checked
    $form.Dispose()
}

if ([string]::IsNullOrWhiteSpace($InstallDir)) { $InstallDir = $DefaultDir }
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)

# --------------------------------------------------------------- 2. elevation

# Program Files needs an administrator; anything the user can already write does not, and asking
# for a prompt nobody needs is how a one-click install stops feeling like one.
$needsAdmin = $false
try {
    $probe = $InstallDir
    while (-not (Test-Path $probe) -and -not [string]::IsNullOrWhiteSpace([System.IO.Path]::GetDirectoryName($probe))) {
        $probe = [System.IO.Path]::GetDirectoryName($probe)
    }
    $acl = Get-Acl $probe
    $needsAdmin = -not (Test-Path $InstallDir) -and ($probe -like "$env:ProgramFiles*" -or $probe -like "${env:ProgramFiles(x86)}*")
} catch { $needsAdmin = $false }

if ($needsAdmin -and -not (Test-Admin)) {
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"",
                 "-InstallDir", "`"$InstallDir`"", "-DisplayVersion", "`"$DisplayVersion`"")
    if ($Silent) { $argList += "-Silent" }
    if (-not $desktop) { $argList += "-NoDesktopShortcut" }
    if (-not $launch) { $argList += "-NoLaunch" }
    $argList += @("-StartMenuName", "`"$StartMenuName`"", "-RegistryKey", "`"$RegistryKey`"")
    try {
        $p = Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $argList -PassThru -Wait
        exit $p.ExitCode
    } catch {
        Show-Error "需要管理员权限才能安装到`n$InstallDir`n`n可以换一个不需要管理员的位置（例如用户目录），或右键以管理员身份运行。"
        exit 1
    }
}

# --------------------------------------------------------------- 3. install

$progressForm = $null
$status = $null
if (-not $Silent) {
    $progressForm = New-Object System.Windows.Forms.Form
    $progressForm.Text = "$AppName 安装程序"
    $progressForm.Size = New-Object System.Drawing.Size(460, 150)
    $progressForm.StartPosition = "CenterScreen"
    $progressForm.FormBorderStyle = "FixedDialog"
    $progressForm.ControlBox = $false
    $progressForm.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 9)
    if (Test-Path $Icon) { $progressForm.Icon = New-Object System.Drawing.Icon($Icon) }

    $status = New-Object System.Windows.Forms.Label
    $status.Text = "正在准备..."
    $status.Location = New-Object System.Drawing.Point(20, 22)
    $status.Size = New-Object System.Drawing.Size(410, 22)
    $progressForm.Controls.Add($status)

    $bar = New-Object System.Windows.Forms.ProgressBar
    $bar.Style = "Marquee"
    $bar.MarqueeAnimationSpeed = 30
    $bar.Location = New-Object System.Drawing.Point(22, 56)
    $bar.Size = New-Object System.Drawing.Size(404, 20)
    $progressForm.Controls.Add($bar)

    $progressForm.Show()
    [System.Windows.Forms.Application]::DoEvents()
}

function Set-Status([string]$text) {
    if ($status) {
        $status.Text = $text
        [System.Windows.Forms.Application]::DoEvents()
    }
    Write-Log $text
    Write-Output $text
}

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("dstokencheck-setup-" + [Guid]::NewGuid().ToString("N"))
try {
    if (-not (Test-Path $Payload)) { throw "安装包缺少 payload.zip，无法继续。" }

    Write-Log "setup start: InstallDir=$InstallDir silent=$Silent payload=$Payload"
    Set-Status "正在解压程序文件..."
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Payload, $temp)

    Set-Status "正在写入 $InstallDir ..."
    if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null }
    Copy-Item -Path (Join-Path $temp "*") -Destination $InstallDir -Recurse -Force

    Set-Status "正在创建快捷方式..."
    $target = Join-Path $InstallDir "runtime\bin\javaw.exe"
    $arguments = "-jar `"$(Join-Path $InstallDir $ExeName)`""
    $shell = New-Object -ComObject WScript.Shell

    # Per-machine when we are elevated (the default Program Files location), per-user otherwise:
    # that is where Windows expects each kind to live, and it keeps a user-dir install admin-free.
    $perMachine = Test-Admin
    $startMenuDir = if (-not [string]::IsNullOrWhiteSpace($StartMenuDir)) {
        $StartMenuDir
    } elseif ($perMachine) {
        Join-Path ([Environment]::GetFolderPath("CommonStartMenu")) "Programs"
    } else {
        Join-Path ([Environment]::GetFolderPath("StartMenu")) "Programs"
    }
    if (-not (Test-Path $startMenuDir)) { New-Item -ItemType Directory -Force -Path $startMenuDir | Out-Null }
    $link = $shell.CreateShortcut((Join-Path $startMenuDir "$StartMenuName.lnk"))
    $link.TargetPath = $target
    $link.Arguments = $arguments
    $link.WorkingDirectory = $InstallDir
    $link.Description = $AppName
    if (Test-Path (Join-Path $InstallDir "app.ico")) { $link.IconLocation = (Join-Path $InstallDir "app.ico") }
    $link.Save()

    if ($desktop) {
        $desktopDir = if (-not [string]::IsNullOrWhiteSpace($DesktopDir)) {
            $DesktopDir
        } elseif ($perMachine) {
            [Environment]::GetFolderPath("CommonDesktopDirectory")
        } else {
            [Environment]::GetFolderPath("Desktop")
        }
        if ($desktopDir) {
            $dlink = $shell.CreateShortcut((Join-Path $desktopDir "$StartMenuName.lnk"))
            $dlink.TargetPath = $target
            $dlink.Arguments = $arguments
            $dlink.WorkingDirectory = $InstallDir
            $dlink.Description = $AppName
            if (Test-Path (Join-Path $InstallDir "app.ico")) { $dlink.IconLocation = (Join-Path $InstallDir "app.ico") }
            $dlink.Save()
        }
    }

    Set-Status "正在登记卸载信息..."
    $size = [int]((Get-ChildItem $InstallDir -Recurse -File | Measure-Object Length -Sum).Sum / 1KB)
    $root = if ($perMachine) { "HKLM:" } else { "HKCU:" }
    $key = "$root\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$RegistryKey"
    New-Item -Path $key -Force | Out-Null
    $uninstaller = Join-Path $InstallDir "uninstall.exe"
    Set-ItemProperty -Path $key -Name "DisplayName"     -Value $AppName
    Set-ItemProperty -Path $key -Name "DisplayVersion"  -Value $DisplayVersion
    Set-ItemProperty -Path $key -Name "Publisher"       -Value "dstokencheck"
    Set-ItemProperty -Path $key -Name "InstallLocation" -Value $InstallDir
    Set-ItemProperty -Path $key -Name "DisplayIcon"     -Value (Join-Path $InstallDir "app.ico")
    Set-ItemProperty -Path $key -Name "EstimatedSize"   -Value $size -Type DWord
    Set-ItemProperty -Path $key -Name "NoModify"        -Value 1 -Type DWord
    Set-ItemProperty -Path $key -Name "NoRepair"        -Value 1 -Type DWord
    if (Test-Path $uninstaller) { Remove-Item $uninstaller -Force -ErrorAction SilentlyContinue }
    $script = Join-Path $InstallDir "uninstall.ps1"
    # The path is baked into the command on purpose: the uninstaller runs from a hidden
    # PowerShell with no idea where it came from, and guessing is how an uninstaller deletes
    # the wrong thing.
    $quiet = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`" -Dir `"$InstallDir`""
    Set-ItemProperty -Path $key -Name "UninstallString"      -Value $quiet
    Set-ItemProperty -Path $key -Name "QuietUninstallString" -Value "$quiet -Silent"

    if ($progressForm) { $progressForm.Close(); $progressForm.Dispose(); $progressForm = $null }

    if ($launch) {
        $userArgs = "-jar `"$(Join-Path $InstallDir $ExeName)`""
        Start-Process -FilePath $target -ArgumentList $userArgs -WorkingDirectory $InstallDir | Out-Null
    }

    Show-Info "$AppName 已安装完成。`n`n安装位置：$InstallDir`n`n可以在开始菜单里找到它；右键窗口菜单里可以打开「开机自启」。"
    exit 0
} catch {
    if ($progressForm) { $progressForm.Close(); $progressForm.Dispose() }
    Write-Log ("FAILED: " + $_.Exception.ToString())
    Show-Error "安装失败：`n$($_.Exception.Message)"
    try { Stop-Transcript | Out-Null } catch { }
    exit 1
} finally {
    if (Test-Path $temp) { Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue }
}
