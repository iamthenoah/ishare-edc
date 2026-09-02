function Start-Scenario {
    param(
        [Parameter(Mandatory)][int]$Scenario,
        [Parameter(Mandatory)][string]$ArTransport,
        [Parameter(Mandatory)][string]$ConsumerTransport,
        [Parameter(Mandatory)][string]$ProviderTransport,
        [Parameter(Mandatory)][string]$BridgeProvider,
        [Parameter(Mandatory)][string]$BridgeConsumer,

        [string]$IdentityMode = "token",

        [string]$WalletTransport = "http"
    )

    $ErrorActionPreference = "Stop"

    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    Set-Location $repoRoot

    Write-Host "Scenario ${Scenario}: identity=$IdentityMode ar.transport=$ArTransport consumer.transport=$ConsumerTransport provider.transport=$ProviderTransport bridge(provider)=$BridgeProvider bridge(consumer)=$BridgeConsumer"

    $vcMode = ($IdentityMode -eq "vc")

    $providerVcArgs = @()
    $consumerVcArgs = @()
    if ($vcMode) {
        $commonVcArgs = @(
            "-Dishare.identity.mode=vc",
            "-Dishare.wallet.transport=$WalletTransport",
            "-Dishare.vc.issuer.id=EU.EORI.NLTESTISSUER",
            "-Dishare.vc.issuer.cert-path=resources/certs/ar_cert.pem"
        )
        $providerVcArgs = $commonVcArgs + @("-Dishare.wallet.base.url=http://localhost:7006")
        $consumerVcArgs = $commonVcArgs + @("-Dishare.wallet.base.url=http://localhost:7007")
    }

    $akkaSeed = "akka://ishare-cluster@127.0.0.1:25251"
    $logDir = "logs\scenario-$Scenario"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null

    function Start-JavaProcess {
        param([string]$Name, [string[]]$JavaArgs)
        $outLog = Join-Path $logDir "$Name.log"
        $errLog = Join-Path $logDir "$Name.err.log"
        $proc = Start-Process -FilePath "java" -ArgumentList $JavaArgs `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
            -NoNewWindow -PassThru
        $proc.Id | Out-File -Encoding ascii (Join-Path $logDir "$Name.pid")
        return $proc
    }

    function Invoke-Checked {
        param([string]$FilePath, [string[]]$ArgList)
        & $FilePath @ArgList
        if ($LASTEXITCODE -ne 0) {
            throw "$FilePath $($ArgList -join ' ') failed with exit code $LASTEXITCODE"
        }
    }

    Start-JavaProcess -Name "provider-connector" -JavaArgs (@(
        "-Dedc.fs.config=extensions/connector-provider/src/main/resources/configuration/provider-configuration.properties",
        "-Dedc.vault=extensions/connector-provider/src/main/resources/configuration/provider-vault.properties",
        "-Dedc.keystore=resources/certs/provider.p12",
        "-Dedc.keystore.password=changeit",
        "-Dishare.ar.transport=$ArTransport",
        "-Dishare.management.bridge.role=$BridgeProvider",
        "-Dishare.akka.host=127.0.0.1",
        "-Dishare.akka.port=25251",
        "-Dishare.akka.seed-nodes=$akkaSeed") + $providerVcArgs + @(
        "-jar", "extensions/connector-provider/build/libs/connector-provider.jar"
    )) | Out-Null

    Start-JavaProcess -Name "consumer-connector" -JavaArgs (@(
        "-Dedc.fs.config=extensions/connector-consumer/src/main/resources/configuration/consumer-configuration.properties",
        "-Dedc.vault=extensions/connector-consumer/src/main/resources/configuration/consumer-vault.properties",
        "-Dedc.keystore=resources/certs/consumer.p12",
        "-Dedc.keystore.password=changeit",
        "-Dishare.ar.transport=$ArTransport",
        "-Dishare.management.bridge.role=$BridgeConsumer",
        "-Dishare.akka.host=127.0.0.1",
        "-Dishare.akka.port=25252",
        "-Dishare.akka.seed-nodes=$akkaSeed",
        "-Dishare.bench.port=29199") + $consumerVcArgs + @(
        "-jar", "extensions/connector-consumer/build/libs/connector-consumer.jar"
    )) | Out-Null

    Write-Host "Waiting 8s for connectors to come up..."
    Start-Sleep -Seconds 8

    if ($vcMode) {
        Start-JavaProcess -Name "issuer-app" -JavaArgs @(
            "-Dissuer.transport=$WalletTransport",
            "-Dissuer.id=EU.EORI.NLTESTISSUER",
            "-Dissuer.private-key-path=resources/certs/ar_private_key.pem",
            "-Dissuer.cert-path=resources/certs/ar_cert.pem",
            "-Dissuer.port=7005",
            "-Dissuer.akka.port=25256",
            "-Dissuer.akka.seed-nodes=$akkaSeed",
            "-jar", "apps/issuer/build/libs/issuer.jar"
        ) | Out-Null

        Write-Host "Waiting 5s for the issuer before starting wallets..."
        Start-Sleep -Seconds 5

        Start-JavaProcess -Name "wallet-provider" -JavaArgs @(
            "-Dwallet.party-id=EU.EORI.NLTESTPROVIDER",
            "-Dwallet.role=ServiceProvider",
            "-Dwallet.private-key-path=resources/certs/provider_private_key.pem",
            "-Dwallet.cert-path=resources/certs/provider_cert.pem",
            "-Dwallet.transport=$WalletTransport",
            "-Dwallet.issuer.transport=$WalletTransport",
            "-Dwallet.issuer.url=http://localhost:7005",
            "-Dwallet.port=7006",
            "-Dwallet.akka.port=25257",
            "-Dwallet.akka.seed-nodes=$akkaSeed",
            "-jar", "apps/wallet/build/libs/wallet.jar"
        ) | Out-Null

        Start-JavaProcess -Name "wallet-consumer" -JavaArgs @(
            "-Dwallet.party-id=EU.EORI.NLTESTCONSUMER",
            "-Dwallet.role=ServiceConsumer",
            "-Dwallet.private-key-path=resources/certs/consumer_private_key.pem",
            "-Dwallet.cert-path=resources/certs/consumer_cert.pem",
            "-Dwallet.transport=$WalletTransport",
            "-Dwallet.issuer.transport=$WalletTransport",
            "-Dwallet.issuer.url=http://localhost:7005",
            "-Dwallet.port=7007",
            "-Dwallet.akka.port=25258",
            "-Dwallet.akka.seed-nodes=$akkaSeed",
            "-jar", "apps/wallet/build/libs/wallet.jar"
        ) | Out-Null
    }
    else {
        Start-JavaProcess -Name "ar-app" -JavaArgs @(
            "-Dar.transport=$ArTransport",
            "-Dar.eori=EU.EORI.NLTESTAR",
            "-Dar.private-key-path=resources/certs/ar_private_key.pem",
            "-Dar.cert-path=resources/certs/ar_cert.pem",
            "-Dar.akka.seed-nodes=$akkaSeed",
            "-jar", "apps/ar/build/libs/ar.jar"
        ) | Out-Null
    }

    Start-JavaProcess -Name "provider-app" -JavaArgs @(
        "-Dprovider.transport=$ProviderTransport",
        "-Dprovider.akka.seed-nodes=$akkaSeed",
        "-Dprovider.port=7001",
        "-jar", "apps/provider/build/libs/provider.jar"
    ) | Out-Null

    Start-Sleep -Seconds 5

    Start-JavaProcess -Name "consumer-app" -JavaArgs @(
        "-Dconsumer.transport=$ConsumerTransport",
        "-Dconsumer.akka.seed-nodes=$akkaSeed",
        "-Dconsumer.port=7002",
        "-Dconsumer.transfer-type=HttpData-PUSH",
        "-Dconsumer.destination-type=HttpData",
        "-Dconsumer.destination-base-url=http://localhost:7002/receive",
        "-Dconsumer.poll-timeout-seconds=120",
        "-jar", "apps/consumer/build/libs/consumer.jar"
    ) | Out-Null

    Write-Host "All processes started. Logs + PIDs in $logDir\."
    Write-Host "Waiting 6s for apps to finish starting before running :init tasks..."
    Start-Sleep -Seconds 6

    Write-Host ""
    Write-Host "=== Seeding/verifying via :init ==="
    $gradlew = Join-Path $repoRoot "gradlew.bat"
    Invoke-Checked $gradlew @("--console=plain", ":init:initProvider")
    if (-not $vcMode) {
        Invoke-Checked $gradlew @("--console=plain", ":init:initAr", "-Dar.transport=$ArTransport")
    }
    Invoke-Checked $gradlew @("--console=plain", ":init:consumerFlow")

    Write-Host ""
    Write-Host "Scenario $Scenario fully up and seeded. Stop with:"
    Write-Host "  Get-Content $logDir\*.pid | ForEach-Object { Stop-Process -Id `$_ -Force }"
}
