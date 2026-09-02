$ErrorActionPreference = "Stop"
. "$PSScriptRoot\lib\scenario-common.ps1"

Start-Scenario -Scenario 2 -ArTransport akka -ConsumerTransport akka -ProviderTransport akka -BridgeProvider provider -BridgeConsumer consumer
