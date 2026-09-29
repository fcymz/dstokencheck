param(
    [string]$JavaArgs = "",
    [string]$Tag = "login",
    [switch]$SkipResize
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Become DPI aware so GetWindowRect and CopyFromScreen agree with Java's coordinates.
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class WinCap {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hWnd, int x, int y, int w, int h, bool repaint);
    [DllImport("user32.dll")] public static extern bool RedrawWindow(IntPtr hWnd, IntPtr r, IntPtr rgn, uint flags);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, System.Text.StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
    public static List<IntPtr> ForProcess(uint target) {
        List<IntPtr> found = new List<IntPtr>();
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            uint pid;
            GetWindowThreadProcessId(h, out pid);
            if (pid == target && IsWindowVisible(h)) found.Add(h);
            return true;
        }, IntPtr.Zero);
        return found;
    }
    public static string Title(IntPtr h) {
        System.Text.StringBuilder sb = new System.Text.StringBuilder(512);
        GetWindowTextW(h, sb, sb.Capacity);
        return sb.ToString();
    }
}
"@

[void][WinCap]::SetProcessDPIAware()

# Run the app against a THROWAWAY home directory. It starts with no saved key, which is what this
# tool wants (the login window), and it never touches the user's real config.
#
# This used to `Remove-Item` the real config to get a clean start -- which silently destroyed the
# user's saved API key and settings. Never do that again: point user.home somewhere else instead.
$probeHome = Join-Path $env:TEMP 'dstokencheck-capture-home'
Remove-Item $probeHome -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $probeHome | Out-Null

# Resolve the JVM and jar relative to this script so the tool is portable.
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target\dstokencheck.jar'
$java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if (-not $java) { throw 'java.exe not found on PATH' }
if (-not (Test-Path $jar)) { throw "jar not found: $jar (run 'mvn package' first)" }
$out = Join-Path $env:TEMP 'ds-cap-out.txt'

$launchArgs = @("-Duser.home=$probeHome", '-jar', $jar)
if ($JavaArgs -ne "") { $launchArgs += ($JavaArgs -split ' ') }
$p = Start-Process -FilePath $java -ArgumentList $launchArgs `
    -PassThru -RedirectStandardOutput $out -RedirectStandardError "$out.err" -WindowStyle Hidden
Write-Output "launched pid=$($p.Id) args=$JavaArgs"
Start-Sleep -Seconds 8

if ($p.HasExited) {
    Write-Output "EXITED EARLY code=$($p.ExitCode)"
    Get-Content $out, "$out.err" -ErrorAction SilentlyContinue
    exit 1
}

Add-Type -AssemblyName System.Drawing

function Capture-All([string]$suffix) {
    $windows = [WinCap]::ForProcess([uint32]$script:targetPid)
    Write-Output "visible windows: $($windows.Count) [$suffix]"
    $i = 0
    foreach ($h in $windows) {
        $title = [WinCap]::Title($h)
        $r = New-Object WinCap+RECT
        [void][WinCap]::GetWindowRect($h, [ref]$r)
        $w = $r.Right - $r.Left
        $hh = $r.Bottom - $r.Top
        Write-Output "  [$i] title='$title' rect=$($r.Left),$($r.Top) size=${w}x${hh}"
        if ($w -gt 0 -and $hh -gt 0) {
            $rect = New-Object System.Drawing.Rectangle $r.Left, $r.Top, $w, $hh
            $bmp = New-Object System.Drawing.Bitmap $w, $hh
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.CopyFromScreen($rect.Location, [System.Drawing.Point]::Empty, $rect.Size)
            $png = Join-Path $PSScriptRoot "live-$script:tag-$suffix-$i.png"
            $bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
            $g.Dispose(); $bmp.Dispose()
            Write-Output "      saved $png"
        }
        $i++
    }
}

$script:targetPid = $p.Id
$script:tag = $Tag
Capture-All 'a-initial'

# Force a resize so Java performs a fresh full layout + repaint, then capture again.
$windows = [WinCap]::ForProcess([uint32]$p.Id)
foreach ($h in $windows) {
    $r = New-Object WinCap+RECT
    [void][WinCap]::GetWindowRect($h, [ref]$r)
    $w = $r.Right - $r.Left; $hh = $r.Bottom - $r.Top
    [void][WinCap]::MoveWindow($h, $r.Left + 40, $r.Top + 40, $w, $hh, $true)
}
Start-Sleep -Seconds 2
Capture-All 'b-after-resize'

Stop-Process -Id $p.Id -Force
Write-Output "stopped"
