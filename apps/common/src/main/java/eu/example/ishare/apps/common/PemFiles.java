package eu.example.ishare.apps.common;

import java.io.ByteArrayInputStream;
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

public final class PemFiles {
    private PemFiles() {}

    public static PrivateKey loadPrivateKey(Path path) throws Exception {
        String pem = Files.readString(path);
        Matcher matcher = Pattern
                .compile("-----BEGIN (?:RSA )?PRIVATE KEY-----(.+?)-----END (?:RSA )?PRIVATE KEY-----", Pattern.DOTALL)
                .matcher(pem);
        if (!matcher.find()) {
            throw new IllegalStateException("No private key found in " + path);
        }
        byte[] der = Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s+", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    public static List<X509Certificate> loadCertChain(Path path) throws Exception {
        String pem = Files.readString(path);
        Matcher matcher = Pattern
                .compile("-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", Pattern.DOTALL)
                .matcher(pem);
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        while (matcher.find()) {
            byte[] bytes = ("-----BEGIN CERTIFICATE-----" + matcher.group(1) + "-----END CERTIFICATE-----")
                    .getBytes(StandardCharsets.UTF_8);
            chain.add((X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(bytes)));
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException("No certificates found in " + path);
        }
        return chain;
    }
}
