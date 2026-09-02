param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("1", "2", "3", "4")]
    [string]$Scenario,
    [string]$Modes = "ping,catalog,run",
    [string]$Concurrency = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16",
    [int]$DurationSeconds = 60,
    [int]$Repeats = 3
)

$ErrorActionPreference = "Stop"
Set-Location "$PSScriptRoot/.."

& .\gradlew.bat --console=plain :perf:perfRun `
    "-Dperf.scenario=$Scenario" `
    "-Dperf.mode=$Modes" `
    "-Dperf.concurrency=$Concurrency" `
    "-Dperf.duration-seconds=$DurationSeconds" `
    "-Dperf.repeats=$Repeats"

Write-Host ""
Write-Host "Done. Raw samples + summary.csv in results\perf\scenario-$Scenario\."
Write-Host "Cross-scenario table: .\gradlew.bat :perf:perfCompare  ->  results\perf\comparison.md"
