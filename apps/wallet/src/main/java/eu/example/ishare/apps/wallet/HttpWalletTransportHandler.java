package eu.example.ishare.apps.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.example.ishare.apps.common.JsonSupport;
import eu.example.ishare.domain.vc.WalletService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;

public final class HttpWalletTransportHandler implements WalletTransportHandler {
    private final int port;
    private WalletService service;

    public HttpWalletTransportHandler(int port) {
        this.port = port;
    }

    @Override
    public void start(WalletService service) throws IOException {
        this.service = service;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/presentations", this::handlePresentations);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.printf("Wallet (http transport) running on http://localhost:%d%n", port);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        JsonSupport.writeJson(exchange, 200, Map.of(
                "status", "up",
                "hasCredential", service.hasCredential(),
                "time", Instant.now().toString()));
    }

    private void handlePresentations(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        JsonNode body = JsonSupport.readJson(exchange);
        String audience = body.path("audience").asText("");

        WalletService.PresentationResult result = service.createPresentation(audience);
        if (!result.isSuccess()) {
            JsonSupport.writeJson(exchange, 400, Map.of("error", result.error()));
            return;
        }
        JsonSupport.writeJson(exchange, 201, Map.of("presentation", result.presentationJwt()));
    }
}
