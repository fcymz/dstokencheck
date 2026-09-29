# ---------------------------------------------------------------------------
#  Verifies the widget stays above the Windows taskbar.
#
#  setAlwaysOnTop(true) only sets WS_EX_TOPMOST. The taskbar is topmost too, and
#  inside that band the most recently activated window wins -- so clicking the
#  taskbar covers the widget even though isAlwaysOnTop() still reports true.
#  This script reproduces that (by raising the taskbar) and then checks that the
#  widget's guard timer re-seats it above the taskbar.
#
#  Usage:  powershell -ExecutionPolicy Bypass -File tools\verify-topmost.ps1
# ---------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public class ZOrder {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowW(string cls, string win);
    [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr h, uint cmd);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);

    public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
    public const uint SWP_NOMOVE = 0x0002, SWP_NOSIZE = 0x0001, SWP_NOACTIVATE = 0x0010;
    public const uint GW_HWNDPREV = 3;

    public static List<IntPtr> ForPid(uint target) {
        List<IntPtr> f = new List<IntPtr>();
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            uint pid; GetWindowThreadProcessId(h, out pid);
            if (pid == target && IsWindowVisible(h)) f.Add(h);
            return true;
        }, IntPtr.Zero);
        return f;
    }
    public static string Title(IntPtr h) {
        StringBuilder sb = new StringBuilder(512); GetWindowTextW(h, sb, sb.Capacity); return sb.ToString();
    }
    public static string Cls(IntPtr h) {
        StringBuilder sb = new StringBuilder(512); GetClassNameW(h, sb, sb.Capacity); return sb.ToString();
    }
    // Walk up the z-order from 'below'; true when 'above' is reached first.
    public static bool IsAbove(IntPtr above, IntPtr below) {
        IntPtr cur = GetWindow(below, GW_HWNDPREV);
        int guard = 0;
        while (cur != IntPtr.Zero && guard++ < 4000) {
            if (cur == above) return true;
            cur = GetWindow(cur, GW_HWNDPREV);
        }
        return false;
    }
}
"@

$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target\dstokencheck.jar'
$java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if (-not $java) { throw 'java.exe not found on PATH' }
if (-not (Test-Path $jar)) { throw "jar not found: $jar (run 'mvn package' first)" }

# Throwaway home: this tool must not disturb the user's real settings.
$probeHome = Join-Path $env:TEMP 'dstokencheck-topmost-home'
Remove-Item $probeHome -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $probeHome | Out-Null

$p = Start-Process -FilePath $java `
    -ArgumentList @("-Duser.home=$probeHome", '-Ddstokencheck.demo=true', '-jar', "`"$jar`"", '--demo') `
    -PassThru -WindowStyle Hidden
Write-Output "widget pid = $($p.Id)"
Start-Sleep -Seconds 7

try {
    $wins = [ZOrder]::ForPid([uint32]$p.Id) | Where-Object { [ZOrder]::Title($_) -ne '' }
    $widget = $wins | Where-Object { [ZOrder]::Cls($_) -like 'SunAwt*' } | Select-Object -First 1
    if (-not $widget) { $widget = $wins | Select-Object -First 1 }
    if (-not $widget) { throw 'widget window not found' }
    Write-Output "widget hwnd = $widget  title='$([ZOrder]::Title($widget))'"

    $tray = [ZOrder]::FindWindowW('Shell_TrayWnd', $null)
    if ($tray -eq [IntPtr]::Zero) { throw 'taskbar not found' }
    Write-Output "taskbar hwnd = $tray"

    $base = [ZOrder]::IsAbove($widget, $tray)
    Write-Output ""
    Write-Output "baseline:                   widget above taskbar = $base"

    # Simulate clicking the taskbar: raise it to the front of the topmost band.
    [void][ZOrder]::SetWindowPos($tray, [ZOrder]::HWND_TOPMOST, 0, 0, 0, 0,
        ([ZOrder]::SWP_NOMOVE -bor [ZOrder]::SWP_NOSIZE -bor [ZOrder]::SWP_NOACTIVATE))
    Start-Sleep -Milliseconds 250
    $covered = -not [ZOrder]::IsAbove($widget, $tray)
    Write-Output "right after raising taskbar: widget above taskbar = $(-not $covered)"

    # Wait out the guard interval.
    Start-Sleep -Milliseconds 3000
    $restored = [ZOrder]::IsAbove($widget, $tray)
    Write-Output "after guard interval:       widget above taskbar = $restored"

    Write-Output ""
    if (-not $base) {
        Write-Output "RESULT: INCONCLUSIVE - widget was not above the taskbar to begin with"
        exit 2
    } elseif (-not $covered) {
        Write-Output "RESULT: INCONCLUSIVE - could not raise the taskbar above the widget"
        exit 2
    } elseif ($restored) {
        Write-Output "RESULT: PASS - widget re-seated itself above the taskbar"
        exit 0
    } else {
        Write-Output "RESULT: FAIL - taskbar still covers the widget"
        exit 1
    }
} finally {
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    Remove-Item $probeHome -Recurse -Force -ErrorAction SilentlyContinue
}
