param(
    [int[]]$Scenarios = @(1, 2, 3, 4),
    [int[]]$Levels = @(5, 10, 20, 40),
    [int]$LevelDurationSeconds = 30,
    [int]$SettleSeconds = 5,
    [int]$CooldownSeconds = 8
)

Write-Warning "run-all-sweep.ps1 is deprecated; forwarding to run-all-perf.ps1 (Java :perf harness)."
& "$PSScriptRoot\run-all-perf.ps1" -Scenarios $Scenarios -Modes "run" `
    -Concurrency ($Levels -join ",") -DurationSeconds $LevelDurationSeconds `
    -SettleSeconds $SettleSeconds -CooldownSeconds $CooldownSeconds
