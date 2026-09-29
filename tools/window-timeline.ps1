param(
    [string]$JavaArgs = "",
    [int]$Seconds = 10,
    # Keep the user's real settings out of harm's way by default. Pass -UseRealHome when the point
    # of the run IS the saved-key behaviour (you will need to seed that home yourself).
    [switch]$UseRealHome
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class WinList {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, System.Text.StringBuilder s, int n);
    public static List<string> Titles(uint target) {
        List<string> found = new List<string>();
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            uint pid; GetWindowThreadProcessId(h, out pid);
            if (pid == target && IsWindowVisible(h)) {
                System.Text.StringBuilder sb = new System.Text.StringBuilder(512);
                GetWindowTextW(h, sb, sb.Capacity);
                string t = sb.ToString();
                if (t.Length > 0) found.Add(t);
            }
            return true;
        }, IntPtr.Zero);
        return found;
    }
}
"@

# Run against a throwaway home directory so a verification run cannot rewrite the user's settings.
$probeHome = Join-Path $env:TEMP 'dstokencheck-timeline-home'
if ($UseRealHome) {
    $homeArg = @()
    Write-Output "WARNING: -UseRealHome -- this run will read/write your real settings"
} else {
    Remove-Item $probeHome -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $probeHome | Out-Null
    $homeArg = @("-Duser.home=$probeHome")
}

# Resolve the JVM and jar relative to this script so the tool is portable.
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target\dstokencheck.jar'
$java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if (-not $java) { throw 'java.exe not found on PATH' }
if (-not (Test-Path $jar)) { throw "jar not found: $jar (run 'mvn package' first)" }
$out = Join-Path $env:TEMP 'ds-timeline-out.txt'

$launchArgs = $homeArg + @('-jar', $jar)
if ($JavaArgs -ne "") { $launchArgs += ($JavaArgs -split ' ') }

$p = Start-Process -FilePath $java -ArgumentList $launchArgs `
    -PassThru -RedirectStandardOutput $out -RedirectStandardError "$out.err" -WindowStyle Hidden
Write-Output "launched pid=$($p.Id)"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$last = ""
while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
    $titles = [WinList]::Titles([uint32]$p.Id)
    $now = ($titles -join " | ")
    if ($now -ne $last) {
        Write-Output ("  t={0,4:N1}s  {1}" -f $sw.Elapsed.TotalSeconds, $(if ($now -eq "") { "(no window)" } else { $now }))
        $last = $now
    }
    Start-Sleep -Milliseconds 250
}

if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
Write-Output "done"
