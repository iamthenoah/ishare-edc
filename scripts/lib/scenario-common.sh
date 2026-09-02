#!/usr/bin/env bash

set -euo pipefail

launch_scenario() {
  local scenario="$1" ar_transport="$2" consumer_transport="$3" provider_transport="$4" \
        bridge_provider="$5" bridge_consumer="$6" identity_mode="${7:-token}" wallet_transport="${8:-http}"

  cd "$(dirname "${BASH_SOURCE[0]}")/../.."

  echo "Scenario $scenario: identity=$identity_mode ar.transport=$ar_transport consumer.transport=$consumer_transport provider.transport=$provider_transport bridge(provider)=$bridge_provider bridge(consumer)=$bridge_consumer"

  local akka_seed="akka://ishare-cluster@127.0.0.1:25251"
  local logdir="logs/scenario-$scenario"
  mkdir -p "$logdir"

  local provider_vc_args=() consumer_vc_args=()
  if [ "$identity_mode" = "vc" ]; then
    local common_vc_args=(
      -Dishare.identity.mode=vc
      -Dishare.wallet.transport="$wallet_transport"
      -Dishare.vc.issuer.id=EU.EORI.NLTESTISSUER
      -Dishare.vc.issuer.cert-path=resources/certs/ar_cert.pem
    )
    provider_vc_args=("${common_vc_args[@]}" -Dishare.wallet.base.url=http://localhost:7006)
    consumer_vc_args=("${common_vc_args[@]}" -Dishare.wallet.base.url=http://localhost:7007)
  fi

  java -Dedc.fs.config=extensions/connector-provider/src/main/resources/configuration/provider-configuration.properties \
       -Dedc.vault=extensions/connector-provider/src/main/resources/configuration/provider-vault.properties \
       -Dedc.keystore=resources/certs/provider.p12 -Dedc.keystore.password=changeit \
       -Dishare.ar.transport="$ar_transport" -Dishare.management.bridge.role="$bridge_provider" \
       -Dishare.akka.host=127.0.0.1 -Dishare.akka.port=25251 -Dishare.akka.seed-nodes="$akka_seed" \
       ${provider_vc_args[@]+"${provider_vc_args[@]}"} \
       -jar extensions/connector-provider/build/libs/connector-provider.jar > "$logdir/provider-connector.log" 2>&1 &
  echo $! > "$logdir/provider-connector.pid"

  java -Dedc.fs.config=extensions/connector-consumer/src/main/resources/configuration/consumer-configuration.properties \
       -Dedc.vault=extensions/connector-consumer/src/main/resources/configuration/consumer-vault.properties \
       -Dedc.keystore=resources/certs/consumer.p12 -Dedc.keystore.password=changeit \
       -Dishare.ar.transport="$ar_transport" -Dishare.management.bridge.role="$bridge_consumer" \
       -Dishare.akka.host=127.0.0.1 -Dishare.akka.port=25252 -Dishare.akka.seed-nodes="$akka_seed" \
       -Dishare.bench.port=29199 \
       ${consumer_vc_args[@]+"${consumer_vc_args[@]}"} \
       -jar extensions/connector-consumer/build/libs/connector-consumer.jar > "$logdir/consumer-connector.log" 2>&1 &
  echo $! > "$logdir/consumer-connector.pid"

  echo "Waiting 8s for connectors to come up..."
  sleep 8

  if [ "$identity_mode" = "vc" ]; then

    java -Dissuer.transport="$wallet_transport" \
         -Dissuer.id=EU.EORI.NLTESTISSUER \
         -Dissuer.private-key-path=resources/certs/ar_private_key.pem \
         -Dissuer.cert-path=resources/certs/ar_cert.pem \
         -Dissuer.port=7005 -Dissuer.akka.port=25256 -Dissuer.akka.seed-nodes="$akka_seed" \
         -jar apps/issuer/build/libs/issuer.jar > "$logdir/issuer-app.log" 2>&1 &
    echo $! > "$logdir/issuer-app.pid"

    echo "Waiting 5s for the issuer before starting wallets..."
    sleep 5

    java -Dwallet.party-id=EU.EORI.NLTESTPROVIDER -Dwallet.role=ServiceProvider \
         -Dwallet.private-key-path=resources/certs/provider_private_key.pem \
         -Dwallet.cert-path=resources/certs/provider_cert.pem \
         -Dwallet.transport="$wallet_transport" -Dwallet.issuer.transport="$wallet_transport" \
         -Dwallet.issuer.url=http://localhost:7005 \
         -Dwallet.port=7006 -Dwallet.akka.port=25257 -Dwallet.akka.seed-nodes="$akka_seed" \
         -jar apps/wallet/build/libs/wallet.jar > "$logdir/wallet-provider.log" 2>&1 &
    echo $! > "$logdir/wallet-provider.pid"

    java -Dwallet.party-id=EU.EORI.NLTESTCONSUMER -Dwallet.role=ServiceConsumer \
         -Dwallet.private-key-path=resources/certs/consumer_private_key.pem \
         -Dwallet.cert-path=resources/certs/consumer_cert.pem \
         -Dwallet.transport="$wallet_transport" -Dwallet.issuer.transport="$wallet_transport" \
         -Dwallet.issuer.url=http://localhost:7005 \
         -Dwallet.port=7007 -Dwallet.akka.port=25258 -Dwallet.akka.seed-nodes="$akka_seed" \
         -jar apps/wallet/build/libs/wallet.jar > "$logdir/wallet-consumer.log" 2>&1 &
    echo $! > "$logdir/wallet-consumer.pid"
  else

    java -Dar.transport="$ar_transport" \
         -Dar.eori=EU.EORI.NLTESTAR \
         -Dar.private-key-path=resources/certs/ar_private_key.pem \
         -Dar.cert-path=resources/certs/ar_cert.pem \
         -Dar.akka.seed-nodes="$akka_seed" \
         -jar apps/ar/build/libs/ar.jar > "$logdir/ar-app.log" 2>&1 &
    echo $! > "$logdir/ar-app.pid"
  fi

  java -Dprovider.transport="$provider_transport" -Dprovider.akka.seed-nodes="$akka_seed" \
       -Dprovider.port=7001 \
       -jar apps/provider/build/libs/provider.jar > "$logdir/provider-app.log" 2>&1 &
  echo $! > "$logdir/provider-app.pid"

  sleep 5

  java -Dconsumer.transport="$consumer_transport" -Dconsumer.akka.seed-nodes="$akka_seed" \
       -Dconsumer.port=7002 \
       -jar apps/consumer/build/libs/consumer.jar > "$logdir/consumer-app.log" 2>&1 &
  echo $! > "$logdir/consumer-app.pid"

  echo "All processes started. Logs + PIDs in $logdir/."
  echo "Waiting 6s for apps to finish starting before running :init tasks..."
  sleep 6

  echo
  echo "=== Seeding/verifying via :init ==="
  ./gradlew --console=plain :init:initProvider
  if [ "$identity_mode" != "vc" ]; then

    ./gradlew --console=plain :init:initAr -Dar.transport="$ar_transport"
  fi
  ./gradlew --console=plain :init:consumerFlow

  echo
  echo "Scenario $scenario fully up and seeded. Stop with: kill \$(cat $logdir/*.pid)"
}
