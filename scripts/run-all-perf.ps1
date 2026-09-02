param(
    [int[]]$Scenarios = @(1, 2, 3, 4),
    [string]$Modes = "ping,catalog,run",
    [string]$Concurrency = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16",
    [int]$DurationSeconds = 60,
    [int]$Repeats = 3,
    [int]$SettleSeconds = 5,
    [int]$CooldownSeconds = 8
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
. "$repoRoot\scripts\lib\scenario-common.ps1"

$scenarioParams = @{
    1 = @{ Ar = "http"; Consumer = "http"; Provider = "http"; BridgeP = "none";     BridgeC = "none" }
    2 = @{ Ar = "akka"; Consumer = "akka"; Provider = "akka"; BridgeP = "provider"; BridgeC = "consumer" }
    3 = @{ Ar = "akka"; Consumer = "http"; Provider = "http"; BridgeP = "none";     BridgeC = "none" }
    4 = @{ Ar = "http"; Consumer = "akka"; Provider = "akka"; BridgeP = "provider"; BridgeC = "consumer" }
}

function Stop-Scenario {
    param([int]$Scenario)
    $logDir = "logs\scenario-$Scenario"
    if (Test-Path $logDir) {
        Get-ChildItem "$logDir\*.pid" -ErrorAction SilentlyContinue | ForEach-Object {
            $procId = Get-Content $_ -ErrorAction SilentlyContinue
            if ($procId) {
                Stop-Process -Id ([int]$procId) -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

$results = @{}

foreach ($s in $Scenarios) {
    $p = $scenarioParams[$s]
    Write-Host "`n========== Scenario $s ==========`n"

    try {
        Start-Scenario -Scenario $s -ArTransport $p.Ar -ConsumerTransport $p.Consumer `
            -ProviderTransport $p.Provider -BridgeProvider $p.BridgeP -BridgeConsumer $p.BridgeC

        Write-Host "Settling ${SettleSeconds}s before load test..."
        Start-Sleep -Seconds $SettleSeconds

        Write-Host "Running Java load harness against scenario $s (http://localhost:7002)..."
        & .\gradlew.bat --console=plain :perf:perfRun `
            "-Dperf.scenario=$s" `
            "-Dperf.mode=$Modes" `
            "-Dperf.concurrency=$Concurrency" `
            "-Dperf.duration-seconds=$DurationSeconds" `
            "-Dperf.repeats=$Repeats"
        if ($LASTEXITCODE -ne 0) { throw "perfRun exited with $LASTEXITCODE" }

        Write-Host "Scenario $s done. Results in results\perf\scenario-$s\."
        $results[$s] = "OK -> results\perf\scenario-$s\summary.csv"
    }
    catch {
        Write-Warning "Scenario $s failed: $($_.Exception.Message)"
        $results[$s] = "FAILED: $($_.Exception.Message)"
    }
    finally {
        Write-Host "Stopping scenario $s processes..."
        Stop-Scenario -Scenario $s
        Start-Sleep -Seconds $CooldownSeconds
    }
}

Write-Host "`nAggregating cross-scenario comparison..."
& .\gradlew.bat --console=plain :perf:perfCompare

Write-Host "`n========== Summary ==========`n"
foreach ($s in $Scenarios) {
    Write-Host "Scenario $s : $($results[$s])"
}
Write-Host "`nAll done. Per-scenario data under results\perf\scenario-<n>\, comparison in results\perf\comparison.md."
