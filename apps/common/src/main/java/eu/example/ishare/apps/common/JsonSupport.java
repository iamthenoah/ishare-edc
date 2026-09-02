package eu.example.ishare.apps.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class JsonSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode readJson(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            byte[] bytes = inputStream.readAllBytes();
            return bytes.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(bytes);
        }
    }

    public static void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    public static void writeText(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
