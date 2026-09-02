package eu.example.ishare.apps.ar;

import eu.example.ishare.apps.common.AppConfig;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.domain.ar.ArService;
import eu.example.ishare.domain.ar.ArServiceImpl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArApp {
    public static void main(String[] args) throws Exception {
        String arEori = AppConfig.string("ar.eori", "EU.EORI.NLTESTPR");
        String satelliteUrl = AppConfig.string("ar.satellite-url", "http://192.168.77.128:8081");
        String satelliteEori = AppConfig.string("ar.satellite-eori", "EU.EORI.NLTESTPR");
        String keyPath = AppConfig.string("ar.private-key-path", "ar_private_key.pem");
        String certPath = AppConfig.string("ar.cert-path", "ar_cert.pem");

        PrivateKey key = loadPrivateKey(keyPath);
        List<X509Certificate> certs = loadCertChain(certPath);
        ArService service = new ArServiceImpl(arEori, satelliteUrl, satelliteEori, key, certs);

        ArTransportHandler handler = buildTransportHandler(arEori);
        handler.start(service);
    }

    private static ArTransportHandler buildTransportHandler(String arEori) {
        String transport = AppConfig.string("ar.transport", "http");

        if ("akka".equalsIgnoreCase(transport)) {
            String host = AppConfig.string("ar.akka.host", "127.0.0.1");
            int port = AppConfig.integer("ar.akka.port", 25253);
            List<String> seedNodes = ClusterBootstrap.parseSeedNodes(
                    AppConfig.string("ar.akka.seed-nodes", "akka://ishare-cluster@127.0.0.1:25251"));
            return new ActorArTransportHandler(host, port, seedNodes);
        }
        int port = AppConfig.integer("ar.port", 7000);
        return new HttpArTransportHandler(port, arEori);
    }

    private static PrivateKey loadPrivateKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path));

        Matcher m = Pattern
                .compile("-----BEGIN (?:RSA )?PRIVATE KEY-----(.+?)-----END (?:RSA )?PRIVATE KEY-----", Pattern.DOTALL)
                .matcher(pem);

        if (!m.find()) {
            throw new Exception("No private key found in " + path);
        }
        String b64 = m.group(1).replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory
                .getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static List<X509Certificate> loadCertChain(String path) throws Exception {
        String pem = Files.readString(Path.of(path));
        Matcher m = Pattern.compile("-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", Pattern.DOTALL).matcher(pem);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();

        while (m.find()) {
            String block = "-----BEGIN CERTIFICATE-----" + m.group(1) + "-----END CERTIFICATE-----";
            byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
            chain.add((X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(bytes)));
        }
        if (chain.isEmpty()) {
            throw new Exception("No certificates found in " + path);
        }
        return chain;
    }
}
