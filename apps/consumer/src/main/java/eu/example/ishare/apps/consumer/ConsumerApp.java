package eu.example.ishare.apps.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.example.ishare.apps.common.AppConfig;
import eu.example.ishare.apps.common.JsonSupport;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.domain.consumer.CatalogOffer;
import eu.example.ishare.domain.consumer.ConsumerEdcGateway;
import eu.example.ishare.domain.consumer.ConsumerFlowException;
import eu.example.ishare.domain.consumer.ConsumerFlowOrchestrator;
import eu.example.ishare.domain.consumer.ConsumerFlowOrchestrator.FlowRequest;
import eu.example.ishare.domain.consumer.ConsumerFlowOrchestrator.FlowResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ConsumerApp {
    private static final String DEFAULT_PROVIDER_EORI = "EU.EORI.NLTESTPROVIDER";

    private final int port;
    private final URI providerProtocolUrl;
    private final String providerEori;
    private final String transferType;
    private final String destinationType;
    private final String destinationBaseUrl;
    private final int pollTimeoutSeconds;
    private final ConsumerEdcGateway gateway;

    private ConsumerApp(int port, URI providerProtocolUrl, String providerEori, String transferType,
                         String destinationType, String destinationBaseUrl,
                         int pollTimeoutSeconds, ConsumerEdcGateway gateway) {
        this.port = port;
        this.providerProtocolUrl = providerProtocolUrl;
        this.providerEori = providerEori;
        this.transferType = transferType;
        this.destinationType = destinationType;
        this.destinationBaseUrl = destinationBaseUrl;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.gateway = gateway;
    }

    public static void main(String[] args) throws Exception {
        int port = AppConfig.integer("consumer.port", 7002);
        URI providerProtocolUrl = AppConfig.uri("consumer.provider.protocol-url", "http://localhost:19194/protocol");
        String providerEori = AppConfig.string("consumer.provider.eori", DEFAULT_PROVIDER_EORI);

        String transferType = AppConfig.string("consumer.transfer-type", "HttpData-PULL");
        String destinationType = AppConfig.string("consumer.destination-type", "");
        String destinationBaseUrl = AppConfig.string("consumer.destination-base-url", "");
        int pollTimeoutSeconds = AppConfig.integer("consumer.poll-timeout-seconds", 30);

        ConsumerEdcGateway gateway = buildTransportHandler().gateway();

        new ConsumerApp(port, providerProtocolUrl, providerEori, transferType, destinationType,
                destinationBaseUrl, pollTimeoutSeconds, gateway).start();
    }

    private static ConsumerTransportHandler buildTransportHandler() {
        String transport = AppConfig.string("consumer.transport", "http");

        if ("akka".equalsIgnoreCase(transport)) {
            String host = AppConfig.string("consumer.akka.host", "127.0.0.1");
            int akkaPort = AppConfig.integer("consumer.akka.port", 25255);
            List<String> seedNodes = ClusterBootstrap.parseSeedNodes(
                    AppConfig.string("consumer.akka.seed-nodes", "akka://ishare-cluster@127.0.0.1:25251"));

            Duration askTimeout = Duration.ofSeconds(AppConfig.integer("consumer.akka.ask-timeout-seconds", 90));
            Duration discoveryTimeout = Duration.ofSeconds(AppConfig.integer("consumer.akka.discovery-timeout-seconds", 30));
            return new ActorConsumerTransportHandler(host, akkaPort, seedNodes, askTimeout, discoveryTimeout);
        }
        URI managementUrl = AppConfig.uri("consumer.edc.management-url", "http://localhost:29193/management");
        URI benchUrl = AppConfig.uri("consumer.edc.bench-url", "http://localhost:29199/ping");
        return new HttpConsumerTransportHandler(managementUrl, benchUrl);
    }

    private static String text(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.get(fieldName);

        return value == null || value.isNull() || value.asText().isBlank()
                ? defaultValue
                : value.asText();
    }

    private static int integer(JsonNode node, String fieldName, int defaultValue) {
        JsonNode value = node.get(fieldName);

        return value != null && value.canConvertToInt()
                ? value.asInt()
                : defaultValue;
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/run", this::handleRun);
        server.createContext("/catalog", this::handleCatalog);
        server.createContext("/ping", this::handlePing);
        server.createContext("/receive", this::handleReceive);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("Consumer started on http://localhost:%d%n", port);
        System.out.printf("Provider DSP: %s%n", providerProtocolUrl);
    }

    private void handleRun(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        JsonNode requestBody = JsonSupport.readJson(exchange);
        String counterPartyAddress = text(requestBody, "counterPartyAddress", providerProtocolUrl.toString());
        String providerEoriOverride = text(requestBody, "providerEori", providerEori);
        String transferTypeOverride = text(requestBody, "transferType", transferType);
        String destinationTypeOverride = text(requestBody, "destinationType", destinationType);
        String defaultDestinationBaseUrl = destinationBaseUrl.isBlank() ? "http://localhost:" + port + "/receive" : destinationBaseUrl;
        String destinationBaseUrlOverride = text(requestBody, "destinationBaseUrl", defaultDestinationBaseUrl);
        int pollTimeoutSecondsOverride = integer(requestBody, "pollTimeoutSeconds", pollTimeoutSeconds);

        FlowRequest request = new FlowRequest(
                counterPartyAddress, providerEoriOverride,
                transferTypeOverride,
                destinationTypeOverride.isBlank() ? null : destinationTypeOverride,
                destinationTypeOverride.isBlank() ? null : destinationBaseUrlOverride,
                pollTimeoutSecondsOverride
        );
        System.out.printf("[consumer] run start -> counterPartyAddress=%s providerEori=%s%n", counterPartyAddress, providerEoriOverride);

        try {
            FlowResult result = ConsumerFlowOrchestrator.runFlow(gateway, request);
            System.out.printf(
                    "[consumer] run completed -> negotiationId=%s transferId=%s transferState=%s%n",
                    result.negotiationId(), result.transferId(), result.transferState()
            );

            JsonSupport.writeJson(
                    exchange,
                    200,
                    Map.ofEntries(
                            Map.entry("message", "Consumer flow completed"),
                            Map.entry("counterPartyAddress", counterPartyAddress),
                            Map.entry("providerEori", providerEoriOverride),
                            Map.entry("offerId", result.offer().offerId()),
                            Map.entry("assetId", result.offer().assetId()),
                            Map.entry("negotiationId", result.negotiationId()),
                            Map.entry("agreementId", result.agreementId()),
                            Map.entry("negotiationState", result.negotiationState()),
                            Map.entry("transferId", result.transferId()),
                            Map.entry("transferState", result.transferState()),
                            Map.entry("durationMs", result.durationMs())
                    )
            );
        } catch (ConsumerFlowException exception) {
            System.err.printf("[consumer] run FAILED -> %s%n", exception.getMessage());
            JsonSupport.writeJson(
                    exchange,
                    500,
                    Map.of(
                            "error", exception.getMessage(),
                            "providerProtocolUrl", counterPartyAddress
                    )
            );
        }
    }

    private void handlePing(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        exchange.getRequestBody().readAllBytes();
        long startedAt = System.nanoTime();
        try {
            gateway.ping();
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            JsonSupport.writeJson(exchange, 200, Map.of("durationMs", durationMs));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            JsonSupport.writeJson(exchange, 500, Map.of("error", e.getMessage(), "durationMs", durationMs));
        }
    }

    private void handleCatalog(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        JsonNode requestBody = JsonSupport.readJson(exchange);
        String counterPartyAddress = text(requestBody, "counterPartyAddress", providerProtocolUrl.toString());
        String providerEoriOverride = text(requestBody, "providerEori", providerEori);
        long startedAt = System.nanoTime();
        try {
            CatalogOffer offer = gateway.requestCatalog(counterPartyAddress, providerEoriOverride);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            JsonSupport.writeJson(exchange, 200, Map.of(
                    "assetId", offer.assetId(),
                    "offerId", offer.offerId(),
                    "durationMs", durationMs
            ));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            JsonSupport.writeJson(exchange, 500, Map.of("error", e.getMessage(), "durationMs", durationMs));
        }
    }

    private void handleReceive(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.printf("[consumer] received data push:%n%s%n", body);
        JsonSupport.writeJson(exchange, 200, Map.of("message", "Data received"));
    }
}
