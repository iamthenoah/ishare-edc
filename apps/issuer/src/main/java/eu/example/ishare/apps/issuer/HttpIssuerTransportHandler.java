package eu.example.ishare.apps.issuer;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.example.ishare.apps.common.JsonSupport;
import eu.example.ishare.domain.vc.IssuerService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;

public final class HttpIssuerTransportHandler implements IssuerTransportHandler {
    private final int port;
    private IssuerService service;

    public HttpIssuerTransportHandler(int port) {
        this.port = port;
    }

    @Override
    public void start(IssuerService service) throws IOException {
        this.service = service;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/credentials", this::handleCredentials);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.printf("Issuer (http transport) running on http://localhost:%d%n", port);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        JsonSupport.writeJson(exchange, 200, Map.of("status", "up", "time", Instant.now().toString()));
    }

    private void handleCredentials(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonSupport.writeText(exchange, 405, "Method Not Allowed");
            return;
        }
        JsonNode body = JsonSupport.readJson(exchange);
        String subjectId = body.path("subjectId").asText("");
        String role = body.path("role").asText("");

        IssuerService.CredentialResult result = service.issueCredential(subjectId, role);
        if (!result.isSuccess()) {
            JsonSupport.writeJson(exchange, 400, Map.of("error", result.error()));
            return;
        }
        JsonSupport.writeJson(exchange, 201, Map.of("credential", result.credentialJwt()));
    }
}
