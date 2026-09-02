package eu.example.ishare.apps.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class EdcHttpClient {
    private final HttpClient client;

    public EdcHttpClient() {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build());
    }

    public HttpResponse<String> postJson(URI uri, Object body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.toJson(body)))
                .build();
        return send(req);
    }

    public JsonNode getJson(URI uri) throws IOException, InterruptedException {
        return parseChecked(get(uri), uri);
    }

    public JsonNode postJsonAsJson(URI uri, Object body) throws IOException, InterruptedException {
        return parseChecked(postJson(uri, body), uri);
    }

    private static JsonNode parseChecked(HttpResponse<String> response, URI uri) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            throw new IOException(uri + " returned HTTP " + response.statusCode()
                    + (body.isBlank() ? "" : ": " + (body.length() > 500 ? body.substring(0, 500) + "..." : body)));
        }
        return JsonSupport.mapper().readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
