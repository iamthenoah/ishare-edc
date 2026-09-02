package eu.example.ishare.extension.identity;

import akka.actor.typed.ActorSystem;
import okhttp3.OkHttpClient;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.iam.AudienceResolver;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Extension(value = IShareIdentityExtension.NAME)
public class IShareIdentityExtension implements ServiceExtension {
    public static final String NAME = "iSHARE Identity Extension";

    static final String PARTY_ID_KEY = "ishare.party.id";
    static final String PR_BASE_URL_KEY = "ishare.pr.base.url";
    static final String PR_EORI_KEY = "ishare.pr.eori";
    static final String AR_BASE_URL_KEY = "ishare.ar.base.url";
    static final String AR_EORI_KEY = "ishare.ar.eori";
    static final String VAULT_PRIVATE_KEY = "ishare.vault.private.key";
    static final String VAULT_CERTIFICATE = "ishare.vault.certificate";
    static final String DEFAULT_PK_SECRET = "ishare-private-key";
    static final String DEFAULT_CERT_SECRET = "ishare-certificate";
    static final String AR_ENFORCE_KEY = "ishare.ar.enforce";

    static final String AR_TRANSPORT_KEY = "ishare.ar.transport";
    static final String AR_AKKA_ASK_TIMEOUT_SECONDS_KEY = "ishare.ar.akka.ask-timeout-seconds";
    static final String AR_AKKA_DISCOVERY_TIMEOUT_SECONDS_KEY = "ishare.ar.akka.discovery-timeout-seconds";

    static final String IDENTITY_MODE_KEY = "ishare.identity.mode";
    static final String WALLET_TRANSPORT_KEY = "ishare.wallet.transport";
    static final String WALLET_BASE_URL_KEY = "ishare.wallet.base.url";
    static final String WALLET_AKKA_ASK_TIMEOUT_SECONDS_KEY = "ishare.wallet.akka.ask-timeout-seconds";
    static final String WALLET_AKKA_DISCOVERY_TIMEOUT_SECONDS_KEY = "ishare.wallet.akka.discovery-timeout-seconds";
    static final String VC_ISSUER_ID_KEY = "ishare.vc.issuer.id";
    static final String VC_ISSUER_CERT_PATH_KEY = "ishare.vc.issuer.cert-path";

    @Inject private Monitor monitor;
    @Inject private Vault vault;
    @Inject private ActorSystem<Void> akkaSystem;

    @Override
    public String name() { return NAME; }

    @Provider
    public IdentityService iShareIdentityService(ServiceExtensionContext context) {
        String identityMode = context.getSetting(IDENTITY_MODE_KEY, "token");
        if ("vc".equalsIgnoreCase(identityMode)) {
            return buildVcIdentityService(context);
        }
        String partyId = require(context, PARTY_ID_KEY);
        String prBaseUrl = require(context, PR_BASE_URL_KEY);
        String prEori = require(context, PR_EORI_KEY);
        String arBaseUrl = context.getSetting(AR_BASE_URL_KEY, null);
        String arEori = context.getSetting(AR_EORI_KEY, null);
        boolean arEnforce = Boolean.parseBoolean(context.getSetting(AR_ENFORCE_KEY, "false"));
        String pkSecretKey = context.getSetting(VAULT_PRIVATE_KEY, DEFAULT_PK_SECRET);
        String certSecretKey = context.getSetting(VAULT_CERTIFICATE, DEFAULT_CERT_SECRET);
        PrivateKey privateKey = parsePkcs8PrivateKey(resolveRequired(pkSecretKey, "private key"));
        List<X509Certificate> certChain = parseCertChain(resolveRequired(certSecretKey, "certificate chain"));

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        IShareIdentityServiceHandler arHandler = buildArHandler(context, arBaseUrl, http);

        monitor.info(String.format("iSHARE Identity Extension: party=%s  PR=%s (%s)  AR=%s (%s)",
                partyId, prBaseUrl, prEori, arBaseUrl, arEori));

        return new IShareIdentityService(partyId, prBaseUrl, prEori, arEori, arEnforce,
                privateKey, certChain, http, monitor, arHandler);
    }

    private IdentityService buildVcIdentityService(ServiceExtensionContext context) {
        String partyId = require(context, PARTY_ID_KEY);
        String issuerId = context.getSetting(VC_ISSUER_ID_KEY, "EU.EORI.NLTESTISSUER");
        String issuerCertPath = context.getSetting(VC_ISSUER_CERT_PATH_KEY, "resources/certs/ar_cert.pem");

        X509Certificate issuerCert;
        try {
            issuerCert = parseCertChain(Files.readString(Path.of(issuerCertPath))).get(0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read trusted issuer certificate: " + issuerCertPath, e);
        }

        WalletServiceHandler walletHandler = buildWalletHandler(context, partyId);
        monitor.info(String.format("iSHARE Identity Extension: identity mode = vc (party=%s, trusted issuer=%s)",
                partyId, issuerId));
        return new VcIdentityService(partyId, issuerId, issuerCert, walletHandler, monitor);
    }

    private WalletServiceHandler buildWalletHandler(ServiceExtensionContext context, String partyId) {
        String walletTransport = context.getSetting(WALLET_TRANSPORT_KEY, "http");
        if ("akka".equalsIgnoreCase(walletTransport)) {
            monitor.info("iSHARE Identity Extension: wallet transport = akka");
            long askTimeoutSeconds = Long.parseLong(context.getSetting(WALLET_AKKA_ASK_TIMEOUT_SECONDS_KEY, "10"));
            long discoveryTimeoutSeconds = Long.parseLong(context.getSetting(WALLET_AKKA_DISCOVERY_TIMEOUT_SECONDS_KEY, "30"));
            return new ActorWalletServiceHandler(akkaSystem, partyId,
                    Duration.ofSeconds(askTimeoutSeconds), Duration.ofSeconds(discoveryTimeoutSeconds), monitor::info);
        }
        String walletBaseUrl = require(context, WALLET_BASE_URL_KEY);
        monitor.info("iSHARE Identity Extension: wallet transport = http (" + walletBaseUrl + ")");
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        return new HttpWalletServiceHandler(walletBaseUrl, http);
    }

    private IShareIdentityServiceHandler buildArHandler(ServiceExtensionContext context, String arBaseUrl, OkHttpClient http) {
        String arTransport = context.getSetting(AR_TRANSPORT_KEY, "http");
        if ("akka".equalsIgnoreCase(arTransport)) {
            monitor.info("iSHARE Identity Extension: AR transport = akka");
            long askTimeoutSeconds = Long.parseLong(context.getSetting(AR_AKKA_ASK_TIMEOUT_SECONDS_KEY, "10"));
            long discoveryTimeoutSeconds = Long.parseLong(context.getSetting(AR_AKKA_DISCOVERY_TIMEOUT_SECONDS_KEY, "30"));
            return new ActorIShareIdentityServiceHandler(
                    akkaSystem, Duration.ofSeconds(askTimeoutSeconds), Duration.ofSeconds(discoveryTimeoutSeconds), monitor::info);
        }
        if (arBaseUrl != null && !arBaseUrl.isBlank()) {
            monitor.info("iSHARE Identity Extension: AR transport = http (" + arBaseUrl + ")");
            return new HttpIShareIdentityServiceHandler(arBaseUrl, http);
        }
        monitor.info("iSHARE Identity Extension: no AR transport configured");
        return null;
    }

    @Provider
    public AudienceResolver audienceResolver() {
        return message -> Result.success(message.getCounterPartyId());
    }

    private String require(ServiceExtensionContext ctx, String key) {
        String v = ctx.getSetting(key, null);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing required config: " + key);
        return v;
    }

    private String resolveRequired(String secretKey, String description) {
        String secret = vault.resolveSecret(secretKey);
        if (secret != null) return secret;
        String vaultFile = System.getProperty("edc.vault");
        if (vaultFile != null && !vaultFile.isBlank()) {
            Path p = Path.of(vaultFile);
            if (Files.exists(p)) {
                var props = new Properties();
                try (var is = Files.newInputStream(p)) {
                    props.load(is);
                    secret = props.getProperty(secretKey);
                    if (secret != null) return secret;
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read vault file: " + vaultFile, e);
                }
            }
        }
        throw new IllegalStateException("Vault secret '" + secretKey + "' (" + description + ") not found.");
    }

    private PrivateKey parsePkcs8PrivateKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n");
            String b64 = normalized.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s+", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse private key PEM: " + e.getMessage(), e);
        }
    }

    private List<X509Certificate> parseCertChain(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (String part : normalized.split("(?<=-----END CERTIFICATE-----)")) {
                String t = part.trim();
                if (!t.isEmpty()) chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(t.getBytes(StandardCharsets.UTF_8))));
            }
            if (chain.isEmpty()) throw new IllegalStateException("No certificates in PEM");
            return chain;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse cert chain PEM: " + e.getMessage(), e);
        }
    }
}
