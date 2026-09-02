$ErrorActionPreference = "Stop"
. "$PSScriptRoot\lib\scenario-common.ps1"

Start-Scenario -Scenario 4 -ArTransport http -ConsumerTransport akka -ProviderTransport akka -BridgeProvider provider -BridgeConsumer consumer
