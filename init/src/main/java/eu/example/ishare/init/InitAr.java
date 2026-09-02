package eu.example.ishare.init;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import eu.example.ishare.apps.common.AppConfig;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.common.protocol.ArProtocol;
import eu.example.ishare.common.protocol.ArProtocol.AddPolicy;
import eu.example.ishare.common.protocol.ArProtocol.ArCommand;
import eu.example.ishare.common.protocol.ArProtocol.GetPolicies;
import eu.example.ishare.common.protocol.ArProtocol.PolicyResult;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InitAr {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROVIDER_EORI = "EU.EORI.NLTESTPROVIDER";
    private static final String CONSUMER_EORI = "EU.EORI.NLTESTCONSUMER";
    private static final String AR_EORI = "EU.EORI.NLTESTAR";

    private InitAr() {}

    static void run() throws Exception {
        String transport = AppConfig.string("ar.transport", "http");

        if ("akka".equalsIgnoreCase(transport)) {
            runAkka();
        } else {
            runHttp();
        }
    }

    private static void runAkka() throws Exception {
        String host = AppConfig.string("admin.akka.host", "127.0.0.1");
        int port = AppConfig.integer("admin.akka.port", 25260);
        List<String> seedNodes = ClusterBootstrap.parseSeedNodes(
                AppConfig.string("admin.akka.seed-nodes", "akka://ishare-cluster@127.0.0.1:25251"));

        ActorSystem<Void> system = ActorSystem.create(
                Behaviors.empty(), "ishare-cluster", ClusterBootstrap.buildConfig(host, port, seedNodes));
        AtomicReference<ActorRef<ArCommand>> arRef = new AtomicReference<>();
        subscribe(system, arRef);

        System.out.println("Joining ishare-cluster...");
        ActorRef<ArCommand> ar = awaitRef(arRef, Duration.ofSeconds(30));
        System.out.println("AR actor discovered: " + ar);

        try {
            PolicyResult seeded = ask(system, ar, replyTo -> new AddPolicy(policyJson(), "init-ar", replyTo));
            log("SEED POLICY", seeded.success ? seeded.jsonPayload : "FAILED: " + seeded.error);

            PolicyResult listed = ask(system, ar, GetPolicies::new);
            log("LIST POLICIES", listed.success ? listed.jsonPayload : "FAILED: " + listed.error);
        } finally {
            system.terminate();
        }
    }

    private static PolicyResult ask(ActorSystem<Void> system, ActorRef<ArCommand> ar,
                                     akka.japi.function.Function<ActorRef<PolicyResult>, ArCommand> factory) throws Exception {
        var stage = AskPattern.ask(ar, factory, Duration.ofSeconds(10), system.scheduler());
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void subscribe(ActorSystem<Void> system, AtomicReference<ActorRef<ArCommand>> arRef) {
        Behavior<Receptionist.Listing> behavior = Behaviors.receive(Receptionist.Listing.class)
                .onMessage(Receptionist.Listing.class, listing -> {
                    listing.getServiceInstances(ArProtocol.SERVICE_KEY).stream().findFirst().ifPresent(arRef::set);
                    return Behaviors.same();
                }).build();
        var listener = system.systemActorOf(behavior, "ar-listing-listener", Props.empty());
        system.receptionist().tell(Receptionist.subscribe(ArProtocol.SERVICE_KEY, listener));
    }

    private static ActorRef<ArCommand> awaitRef(AtomicReference<ActorRef<ArCommand>> ref, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (ref.get() == null) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("AR actor not discovered");
            Thread.sleep(10);
        }
        return ref.get();
    }

    private static void runHttp() throws Exception {
        String arUrl = AppConfig.string("ar.url", "http://localhost:7000");
        Path certsDir = Path.of(AppConfig.string("init.certs-dir", "resources/certs"));

        PrivateKey providerKey = loadPrivateKey(certsDir.resolve("provider_private_key.pem"));
        List<X509Certificate> providerChain = loadCertChain(certsDir.resolve("provider_cert.pem"));
        List<X509Certificate> arChain = loadCertChain(certsDir.resolve("ar_cert.pem"));

        HttpClient http = HttpClient.newHttpClient();

        System.out.println("Getting AR token...");
        String assertion = buildClientAssertion(PROVIDER_EORI, AR_EORI, providerKey, providerChain, arChain);
        String form = "grant_type=client_credentials&scope=iSHARE&client_id=" + PROVIDER_EORI
                + "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                + "&client_assertion=" + assertion;
        HttpResponse<String> tokenResp = http.send(
                HttpRequest.newBuilder(URI.create(arUrl + "/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (tokenResp.statusCode() != 200) {
            throw new IllegalStateException("AR /token failed: " + tokenResp.statusCode() + " " + tokenResp.body());
        }
        String token = MAPPER.readTree(tokenResp.body()).path("access_token").asText();
        System.out.println("Got token.");

        System.out.println("Registering policy...");
        HttpResponse<String> policyResp = http.send(
                HttpRequest.newBuilder(URI.create(arUrl + "/policies"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(policyJson()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        log("SEED POLICY", policyResp.statusCode() + " " + policyResp.body());

        HttpResponse<String> listResp = http.send(
                HttpRequest.newBuilder(URI.create(arUrl + "/policies"))
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        log("LIST POLICIES", listResp.statusCode() + " " + listResp.body());

        System.out.println("Testing delegation...");
        String delegationBody = MAPPER.writeValueAsString(Map.of("delegationRequest", Map.of(
                "policyIssuer", PROVIDER_EORI,
                "target", Map.of("accessSubject", CONSUMER_EORI),
                "policySets", List.of(Map.of("policies", List.of(Map.of(
                        "target", Map.of("resource", Map.of("type", "x-default", "identifiers", List.of("*"), "attributes", List.of("*")), "actions", List.of("read")),
                        "rules", List.of(Map.of("effect", "Permit"))
                ))))
        )));
        HttpResponse<String> delegResp = http.send(
                HttpRequest.newBuilder(URI.create(arUrl + "/delegation"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(delegationBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        log("DELEGATION", delegResp.statusCode() + " " + delegResp.body());
    }

    private static String policyJson() throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "policyIssuer", PROVIDER_EORI,
                "target", CONSUMER_EORI,
                "policySets", List.of(Map.of("policies", List.of(Map.of(
                        "target", Map.of("resource", Map.of("type", "x-default", "identifiers", List.of("*"), "attributes", List.of("*")), "actions", List.of("read")),
                        "rules", List.of(Map.of("effect", "Permit"))
                ))))
        ));
    }

    private static String buildClientAssertion(String issuer, String audience, PrivateKey key,
                                                List<X509Certificate> ownChain,
                                                List<X509Certificate> counterpartyChain) throws Exception {
        List<Base64> x5c = new ArrayList<>();
        for (X509Certificate cert : ownChain) x5c.add(Base64.encode(cert.getEncoded()));
        for (X509Certificate cert : counterpartyChain) x5c.add(Base64.encode(cert.getEncoded()));

        Instant now = Instant.now();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).x509CertChain(x5c).build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(issuer).audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(30)))
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        String pem = Files.readString(path);
        Matcher m = Pattern
                .compile("-----BEGIN (?:RSA )?PRIVATE KEY-----(.+?)-----END (?:RSA )?PRIVATE KEY-----", Pattern.DOTALL)
                .matcher(pem);
        if (!m.find()) throw new IllegalStateException("No private key found in " + path);

        byte[] der = java.util.Base64.getDecoder().decode(m.group(1).replaceAll("\\s+", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static List<X509Certificate> loadCertChain(Path path) throws Exception {
        String pem = Files.readString(path);
        Matcher m = Pattern.compile("-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", Pattern.DOTALL).matcher(pem);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();

        while (m.find()) {
            byte[] bytes = ("-----BEGIN CERTIFICATE-----" + m.group(1) + "-----END CERTIFICATE-----").getBytes(StandardCharsets.UTF_8);
            chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(bytes)));
        }
        if (chain.isEmpty()) throw new IllegalStateException("No certificates found in " + path);
        return chain;
    }

    private static void log(String label, Object data) {
        System.out.println("\n-- " + label + " " + "-".repeat(Math.max(0, 55 - label.length())));
        System.out.println(data);
    }
}
