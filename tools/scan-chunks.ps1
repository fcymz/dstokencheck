$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Continue'

$js = Get-Content "$env:TEMP\ds_main.js" -Raw
$startIdx = $js.IndexOf('f.u=e=>')
$endIdx = $js.IndexOf('})[e]+".js"', $startIdx)
$mapText = $js.Substring($startIdx, $endIdx - $startIdx)

$entries = [regex]::Matches($mapText, '([0-9]{1,5}):"([0-9a-f]{8,12})"')
Write-Output "chunk entries: $($entries.Count)"

$dir = "$env:TEMP\ds_chunks"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$hits = @()
$i = 0
foreach ($e in $entries) {
    $i++
    $id = $e.Groups[1].Value
    $hash = $e.Groups[2].Value
    $url = "https://fe-static.deepseek.com/platform/static/$id.$hash.js"
    $file = Join-Path $dir "$id.$hash.js"
    if (-not (Test-Path $file)) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $file -TimeoutSec 40 -UseBasicParsing
        } catch {
            Write-Output "FAIL $url : $($_.Exception.Message)"
            continue
        }
    }
    $c = Get-Content $file -Raw
    if ($c -match 'balance|Balance|usage/amount|user_balance') {
        $hits += $file
        Write-Output "HIT chunk $id"
        foreach ($m in [regex]::Matches($c, '.{120}(balance|Balance|usage/amount|user_balance).{120}')) {
            Write-Output ("   >> " + ($m.Value -replace '\s+', ' '))
        }
    }
    if ($i % 50 -eq 0) { Write-Output "...progress $i/$($entries.Count)" }
}
Write-Output "=== total chunks with hits: $($hits.Count) ==="
$hits | ForEach-Object { Write-Output $_ }
