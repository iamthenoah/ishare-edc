$ErrorActionPreference = "Stop"
. "$PSScriptRoot\lib\scenario-common.ps1"

Start-Scenario -Scenario 3 -ArTransport akka -ConsumerTransport http -ProviderTransport http -BridgeProvider none -BridgeConsumer none
