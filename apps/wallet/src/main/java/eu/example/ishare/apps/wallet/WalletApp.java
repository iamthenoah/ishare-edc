package eu.example.ishare.apps.wallet;

import eu.example.ishare.apps.common.ActorIssuerGateway;
import eu.example.ishare.apps.common.AppConfig;
import eu.example.ishare.apps.common.EdcHttpClient;
import eu.example.ishare.apps.common.HttpIssuerGateway;
import eu.example.ishare.apps.common.ManagementActorClient;
import eu.example.ishare.apps.common.PemFiles;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.domain.vc.IssuerGateway;
import eu.example.ishare.domain.vc.WalletService;
import eu.example.ishare.domain.vc.WalletServiceImpl;

import java.net.URI;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

public final class WalletApp {
    public static void main(String[] args) throws Exception {
        String partyId = AppConfig.string("wallet.party-id", "EU.EORI.NLTESTCONSUMER");
        String role = AppConfig.string("wallet.role", "Participant");
        String keyPath = AppConfig.string("wallet.private-key-path", "resources/certs/consumer_private_key.pem");
        String certPath = AppConfig.string("wallet.cert-path", "resources/certs/consumer_cert.pem");
        long presentationValiditySeconds = AppConfig.integer("wallet.presentation-validity-seconds", 300);

        PrivateKey key = PemFiles.loadPrivateKey(Path.of(keyPath));
        List<X509Certificate> certs = PemFiles.loadCertChain(Path.of(certPath));
        WalletService service = new WalletServiceImpl(partyId, key, certs, presentationValiditySeconds);

        obtainCredential(service, partyId, role);

        WalletTransportHandler handler = buildTransportHandler(partyId);
        handler.start(service);
    }

    private static void obtainCredential(WalletService service, String partyId, String role) throws Exception {
        String transport = AppConfig.string("wallet.issuer.transport", "http");

        if ("akka".equalsIgnoreCase(transport)) {
            String host = AppConfig.string("wallet.issuer.akka.host", "127.0.0.1");

            int port = AppConfig.integer("wallet.issuer.akka.port", 0);
            List<String> seedNodes = ClusterBootstrap.parseSeedNodes(
                    AppConfig.string("wallet.issuer.akka.seed-nodes", "akka://ishare-cluster@127.0.0.1:25251"));
            Duration askTimeout = Duration.ofSeconds(AppConfig.integer("wallet.issuer.akka.ask-timeout-seconds", 10));
            Duration discoveryTimeout = Duration.ofSeconds(AppConfig.integer("wallet.issuer.akka.discovery-timeout-seconds", 30));

            try (ActorIssuerGateway gateway = ActorIssuerGateway.start(
                    ManagementActorClient.startClusterClient(host, port, seedNodes), askTimeout, discoveryTimeout)) {
                service.storeCredential(gateway.requestCredential(partyId, role));
            }
        } else {
            URI issuerUrl = AppConfig.uri("wallet.issuer.url", "http://localhost:7005");
            IssuerGateway gateway = new HttpIssuerGateway(issuerUrl, new EdcHttpClient());
            service.storeCredential(gateway.requestCredential(partyId, role));
        }
        System.out.printf("[wallet] credential obtained for %s (role=%s)%n", partyId, role);
    }

    private static WalletTransportHandler buildTransportHandler(String partyId) {
        String transport = AppConfig.string("wallet.transport", "http");

        if ("akka".equalsIgnoreCase(transport)) {
            String host = AppConfig.string("wallet.akka.host", "127.0.0.1");
            int port = AppConfig.integer("wallet.akka.port", 25257);
            List<String> seedNodes = ClusterBootstrap.parseSeedNodes(
                    AppConfig.string("wallet.akka.seed-nodes", "akka://ishare-cluster@127.0.0.1:25251"));
            return new ActorWalletTransportHandler(partyId, host, port, seedNodes);
        }
        int port = AppConfig.integer("wallet.port", 7006);
        return new HttpWalletTransportHandler(port);
    }
}
