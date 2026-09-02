package eu.example.ishare.apps.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.example.ishare.apps.common.AppConfig;
import eu.example.ishare.apps.common.JsonSupport;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.domain.provider.ProviderEdcGateway;
import eu.example.ishare.domain.provider.ProviderSeedOrchestrator;
import eu.example.ishare.domain.provider.ProviderSeedOrchestrator.SeedRequest;
import eu.example.ishare.domain.provider.ProviderSeedOrchestrator.SeedResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ProviderApp {
    public static void main(String[] args) throws Exception {
        int port = AppConfig.integer("provider.port", 7001);
        String publicBaseUrl = AppConfig.string("provider.public.base-url", "http://localhost:" + port);
        String assetId = AppConfig.string("provider.asset-id", "demo-asset");
        String assetName = AppConfig.string("provider.asset-name", "Demo Asset");
        String description = AppConfig.string("provider.description", "Demo data exposed through a lightweight provider app");
        String policyId = AppConfig.string("provider.policy-id", "open-policy");
        String contractDefinitionId = AppConfig.string("provider.contract-definition-id", "demo-contract");
        String content = AppConfig.string("provider.content", "Hello from the provider app");
        String contentUrl = publicBaseUrl + "/content/" + assetId;

        ProviderTransportHandler handler = buildTransportHandler();
        ProviderEdcGateway gateway = handler.gateway();

        SeedRequest seedRequest = new SeedRequest(assetId, assetName, description, contentUrl, policyId, contractDefinitionId);
        System.out.println("[provider] seeding (ProviderSeedOrchestrator)...");
        SeedResult seedResult = ProviderSeedOrchestrator.seed(gateway, seedRequest);
        System.out.println("[provider] asset created -> " + seedResult.createdAssetId());
        System.out.println("[provider] policy created -> " + seedResult.createdPolicyId());
        System.out.println("[provider] contract definition created -> " + seedResult.createdContractDefinitionId());

        startContentServer(port, assetId, content);
        System.out.printf("Provider started on http://localhost:%d%n", port);
    }

    private static ProviderTransportHandler buildTransportHandler() {
        String transport = AppConfig.string("provider.transport", "http");

        if ("akka".equalsIgnoreCase(transport)) {
            String host = AppConfig.string("provider.akka.host", "127.0.0.1");
            int akkaPort = AppConfig.integer("provider.akka.port", 25254);
            List<String> seedNodes = ClusterBootstrap.parseSeedNodes(
                    AppConfig.string("provider.akka.seed-nodes", "akka://ishare-cluster@127.0.0.1:25251"));
            Duration askTimeout = Duration.ofSeconds(AppConfig.integer("provider.akka.ask-timeout-seconds", 10));
            Duration discoveryTimeout = Duration.ofSeconds(AppConfig.integer("provider.akka.discovery-timeout-seconds", 30));
            return new ActorProviderTransportHandler(host, akkaPort, seedNodes, askTimeout, discoveryTimeout);
        }
        URI managementUrl = AppConfig.uri("provider.edc.management-url", "http://localhost:19193/management");
        return new HttpProviderTransportHandler(managementUrl);
    }

    private static void startContentServer(int port, String assetId, String content) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/content/" + assetId, exchange -> handleContent(exchange, assetId, content));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.printf("[provider] content server listening on http://localhost:%d/content/%s%n", port, assetId);
    }

    private static void handleContent(HttpExchange exchange, String assetId, String content) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("assetId", assetId);
        response.put("source", "provider-app");
        response.put("message", content);
        response.put("timestamp", Instant.now().toString());

        System.out.printf("[provider] content request served -> assetId=%s%n", assetId);
        JsonSupport.writeJson(exchange, 200, response);
    }
}
