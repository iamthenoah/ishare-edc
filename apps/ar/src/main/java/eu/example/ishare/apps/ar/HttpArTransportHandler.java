package eu.example.ishare.apps.ar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.example.ishare.apps.common.JsonSupport;
import eu.example.ishare.domain.ar.ArService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class HttpArTransportHandler implements ArTransportHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final String arEori;
    private ArService service;

    public HttpArTransportHandler(int port, String arEori) {
        this.port = port;
        this.arEori = arEori;
    }

    @Override
    public void start(ArService service) throws IOException {
        this.service = service;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/capabilities", this::handleCapabilities);
        server.createContext("/token", this::handleToken);
        server.createContext("/delegation", this::handleDelegation);
        server.createContext("/policies", this::handlePolicies);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.printf("AR (http transport) running on http://localhost:%d  eori=%s%n", port, arEori);
    }

    private static String readBody(HttpExchange x) throws IOException {
        return new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> r = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return r;

        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);

            if (kv.length == 2) {
                r.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return r;
    }

    private void handleHealth(HttpExchange x) throws IOException {
        if (!isGet(x)) return;
        JsonSupport.writeJson(x, 200, Map.of("status", "up", "eori", arEori, "time", java.time.Instant.now().toString()));
    }

    private void handleCapabilities(HttpExchange x) throws IOException {
        if (!isGet(x)) return;
        JsonSupport.writeJson(
                x,
                200,
                Map.of(
                        "capabilities",
                        Map.of(
                                "supported_versions",
                                List.of(
                                        Map.of(
                                                "version",
                                                "1.7",
                                                "supported_features",
                                                List.of(Map.of("id", "ar", "feature", "delegation"))
                                        )
                                )
                        )
                )
        );
    }

    private void handleToken(HttpExchange x) throws IOException {
        if (!isMethod(x, "POST")) return;
        Map<String, String> form = parseForm(readBody(x));
        String grantType = form.get("grant_type");
        String clientId = form.get("client_id");
        String clientAssertion = form.get("client_assertion");

        if (!"client_credentials".equals(grantType) || clientId == null || clientAssertion == null) {
            JsonSupport.writeJson(
                    x,
                    400,
                    Map.of(
                            "error",
                            "invalid_request",
                            "description",
                            "grant_type, client_id and client_assertion are required"
                    )
            );
            return;
        }

        ArService.TokenResult result = service.issueToken(clientId, clientAssertion);
        if (!result.isSuccess()) {
            int status = result.unauthorized() ? 401 : 500;
            String errorCode = result.unauthorized() ? "unauthorized_client" : "error";
            JsonSupport.writeJson(x, status, Map.of("error", errorCode, "description", result.error()));
            return;
        }
        JsonSupport.writeJson(
                x,
                200,
                Map.of("access_token", result.accessToken(), "token_type", "Bearer", "expires_in", 3600, "scope", "iSHARE")
        );
    }

    private void handleDelegation(HttpExchange x) throws IOException {
        String token = extractBearerOrRespond(x);
        if (token == null) return;

        String body = readBody(x);
        ArService.DelegationResult result = service.requestDelegation(token, body);

        if (!result.isSuccess()) {
            int status = result.unauthorized() ? 401 : 500;
            String errorCode = result.unauthorized() ? "invalid_token" : "error";
            JsonSupport.writeJson(x, status, Map.of("error", errorCode, "description", result.error()));
            return;
        }
        JsonSupport.writeJson(x, 200, Map.of("delegation_token", result.delegationToken()));
    }

    private void handlePolicies(HttpExchange x) throws IOException {
        String token = extractBearerOrRespond(x);
        if (token == null) return;

        ArService.TokenLookup lookup = service.resolveToken(token);
        if (!lookup.valid()) {
            JsonSupport.writeJson(x, 401, Map.of("error", "invalid_token"));
            return;
        }
        String clientEori = lookup.eori();

        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod().toUpperCase();

        String id = null;
        String prefix = "/policies/";

        if (path.startsWith(prefix) && path.length() > prefix.length()) {
            id = path.substring(prefix.length());
        }
        switch (method) {
            case "GET" -> {
                if (id != null) {
                    ArService.PolicyResult r = service.getPolicy(id);
                    if (r.notFound()) {
                        JsonSupport.writeJson(x, 404, Map.of("error", "Not found"));
                    } else {
                        JsonSupport.writeJson(x, 200, MAPPER.readTree(r.jsonPayload()));
                    }
                } else {
                    ArService.PolicyResult r = service.getPolicies();
                    JsonNode policies = MAPPER.readTree(r.jsonPayload());
                    JsonSupport.writeJson(x, 200, Map.of("policies", policies, "count", policies.size()));
                }
            }
            case "POST" -> {
                String body = readBody(x);
                ArService.PolicyResult r = service.addPolicy(clientEori, body);
                if (!r.success()) {
                    JsonSupport.writeJson(x, 500, Map.of("error", r.error()));
                    return;
                }
                String newId = MAPPER.readTree(r.jsonPayload()).get("policyId").asText();
                JsonSupport.writeJson(x, 201, Map.of("policyId", newId, "message", "Policy created"));
            }
            case "DELETE" -> {
                if (id == null) {
                    JsonSupport.writeJson(x, 400, Map.of("error", "Policy id required in path"));
                    return;
                }
                ArService.PolicyResult r = service.deletePolicy(id);
                if (r.notFound()) {
                    JsonSupport.writeJson(x, 404, Map.of("error", "Not found"));
                } else {
                    JsonSupport.writeJson(x, 200, Map.of("message", "Deleted"));
                }
            }
            default -> JsonSupport.writeText(x, 405, "Method Not Allowed");
        }
    }

    private String extractBearerOrRespond(HttpExchange x) throws IOException {
        String auth = x.getRequestHeaders().getFirst("Authorization");

        if (auth == null || !auth.startsWith("Bearer ")) {
            JsonSupport.writeJson(x, 401, Map.of("error", "missing_token"));
            return null;
        }
        return auth.substring("Bearer ".length()).trim();
    }

    private boolean isGet(HttpExchange x) throws IOException {
        return isMethod(x, "GET");
    }

    private boolean isMethod(HttpExchange x, String method) throws IOException {
        if (!method.equalsIgnoreCase(x.getRequestMethod())) {
            JsonSupport.writeText(x, 405, "Method Not Allowed");
            return false;
        }
        return true;
    }
}
