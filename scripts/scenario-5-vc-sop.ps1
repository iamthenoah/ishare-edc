$ErrorActionPreference = "Stop"
. "$PSScriptRoot\lib\scenario-common.ps1"

Start-Scenario -Scenario 5 -ArTransport http -ConsumerTransport http -ProviderTransport http `
    -BridgeProvider none -BridgeConsumer none -IdentityMode vc -WalletTransport http
