package eu.example.ishare.apps.issuer;

import eu.example.ishare.apps.common.AppConfig;
import eu.example.ishare.apps.common.PemFiles;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.domain.vc.IssuerService;
import eu.example.ishare.domain.vc.IssuerServiceImpl;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

public final class IssuerApp {
    public static void main(String[] args) throws Exception {
        String issuerId = AppConfig.string("issuer.id", "EU.EORI.NLTESTISSUER");
        String keyPath = AppConfig.string("issuer.private-key-path", "resources/certs/ar_private_key.pem");
        String certPath = AppConfig.string("issuer.cert-path", "resources/certs/ar_cert.pem");
        long validitySeconds = AppConfig.integer("issuer.credential-validity-seconds", 3600);

        PrivateKey key = PemFiles.loadPrivateKey(Path.of(keyPath));
        List<X509Certificate> certs = PemFiles.loadCertChain(Path.of(certPath));
        IssuerService service = new IssuerServiceImpl(issuerId, key, certs, validitySeconds);

        IssuerTransportHandler handler = buildTransportHandler();
        handler.start(service);
    }

    private static IssuerTransportHandler buildTransportHandler() {
        String transport = AppConfig.string("issuer.transport", "http");

        if ("akka".equalsIgnoreCase(transport)) {
            String host = AppConfig.string("issuer.akka.host", "127.0.0.1");
            int port = AppConfig.integer("issuer.akka.port", 25256);
            List<String> seedNodes = ClusterBootstrap.parseSeedNodes(
                    AppConfig.string("issuer.akka.seed-nodes", "akka://ishare-cluster@127.0.0.1:25251"));
            return new ActorIssuerTransportHandler(host, port, seedNodes);
        }
        int port = AppConfig.integer("issuer.port", 7005);
        return new HttpIssuerTransportHandler(port);
    }
}
