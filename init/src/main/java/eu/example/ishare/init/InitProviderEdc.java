package eu.example.ishare.init;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.example.ishare.apps.common.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

final class InitProviderEdc {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private InitProviderEdc() {}

    static void run() throws Exception {
        String mgmt = AppConfig.string("provider.management-url", "http://localhost:19193/management");
        String control = AppConfig.string("provider.control-url", "http://localhost:19192/control");

        System.out.println("=== Provider EDC Init ===");
        System.out.println("Management: " + mgmt);
        System.out.println("Control:    " + control);

        registerDataplane(control);
        verifyAssets(mgmt);
        verifyContractDefinitions(mgmt);
        verifyDataplanes(control);

        System.out.println("\nProvider EDC init complete");
    }

    private static void registerDataplane(String control) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                "@id", "provider-dataplane",
                "url", control + "/transfer",
                "allowedSourceTypes", List.of("HttpData"),
                "allowedDestTypes", List.of("HttpData"),
                "allowedTransferTypes", List.of("HttpData-PUSH", "HttpData-PULL")));
        log("REGISTER DATAPLANE", post(control + "/v1/dataplanes", body));
    }

    private static void verifyAssets(String mgmt) throws Exception {
        log("ASSETS", post(mgmt + "/v3/assets/request", querySpec()));
    }

    private static void verifyContractDefinitions(String mgmt) throws Exception {
        log("CONTRACT DEFINITIONS", post(mgmt + "/v3/contractdefinitions/request", querySpec()));
    }

    private static void verifyDataplanes(String control) throws Exception {
        log("DATAPLANES", get(control + "/v1/dataplanes"));
    }

    private static String querySpec() throws Exception {
        return MAPPER.writeValueAsString(Map.of("@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"), "@type", "QuerySpec"));
    }

    private static String post(String url, String body) throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() + " " + resp.body();
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() + " " + resp.body();
    }

    private static void log(String label, String data) {
        System.out.println("\n-- " + label + " " + "-".repeat(Math.max(0, 55 - label.length())));
        System.out.println(data);
    }
}
