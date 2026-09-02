$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

python -c "import lizard" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "lizard module not found, installing..."
    pip install lizard
}

$out = "docs\metrics-report.md"
$env:PYTHONUTF8 = "1"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
python scripts\lizard_report.py | Tee-Object -FilePath $out -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw "lizard_report.py failed" }

Write-Host ""
Write-Host "Report written to $out"
