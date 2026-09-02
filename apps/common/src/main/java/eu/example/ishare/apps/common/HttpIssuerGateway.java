package eu.example.ishare.apps.common;

import com.fasterxml.jackson.databind.JsonNode;
import eu.example.ishare.domain.vc.IssuerGateway;

import java.net.URI;
import java.util.Map;

public final class HttpIssuerGateway implements IssuerGateway {
    private final URI issuerUrl;
    private final EdcHttpClient httpClient;

    public HttpIssuerGateway(URI issuerUrl, EdcHttpClient httpClient) {
        this.issuerUrl = issuerUrl;
        this.httpClient = httpClient;
    }

    @Override
    public String requestCredential(String subjectId, String role) throws Exception {
        JsonNode response = httpClient.postJsonAsJson(
                URI.create(issuerUrl + "/credentials"),
                Map.of("subjectId", subjectId, "role", role));
        String credential = response.path("credential").asText();
        if (credential.isBlank()) {
            throw new Exception("issuer returned no credential: " + response);
        }
        return credential;
    }
}
