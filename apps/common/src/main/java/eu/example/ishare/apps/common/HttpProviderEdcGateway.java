package eu.example.ishare.apps.common;

import eu.example.ishare.domain.provider.ProviderEdcGateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public final class HttpProviderEdcGateway implements ProviderEdcGateway {
    private final URI edcManagementUrl;
    private final EdcHttpClient httpClient;

    public HttpProviderEdcGateway(URI edcManagementUrl, EdcHttpClient httpClient) {
        this.edcManagementUrl = edcManagementUrl;
        this.httpClient = httpClient;
    }

    @Override
    public String createAsset(String assetId, String name, String description, String contentUrl) throws Exception {
        Map<String, Object> assetBody = Map.of(
                "@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                "@id", assetId,
                "properties", Map.of(
                        "name", name,
                        "description", description,
                        "contenttype", "application/json"
                ),
                "dataAddress", Map.of(
                        "type", "HttpData",
                        "baseUrl", contentUrl,
                        "proxyPath", "false",
                        "proxyQueryParams", "false"
                )
        );
        HttpResponse<String> response = httpClient.postJson(URI.create(edcManagementUrl + "/v3/assets"), assetBody);
        ensureSuccessful("create asset", response.statusCode(), 200, 201, 409);
        return assetId;
    }

    @Override
    public String createPolicy(String policyId) throws Exception {
        Map<String, Object> policyBody = Map.of(
                "@context", Map.of(
                        "@vocab", "https://w3id.org/edc/v0.0.1/ns/",
                        "odrl", "http://www.w3.org/ns/odrl/2/"
                ),
                "@id", policyId,
                "policy", Map.of("@type", "odrl:Set", "odrl:permission", List.of())
        );
        HttpResponse<String> response = httpClient.postJson(URI.create(edcManagementUrl + "/v3/policydefinitions"), policyBody);
        ensureSuccessful("create policy", response.statusCode(), 200, 201, 409);
        return policyId;
    }

    @Override
    public String createContractDefinition(String contractDefinitionId, String policyId) throws Exception {
        Map<String, Object> contractDefinitionBody = Map.of(
                "@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                "@id", contractDefinitionId,
                "accessPolicyId", policyId,
                "contractPolicyId", policyId,
                "assetsSelector", List.of()
        );
        HttpResponse<String> response = httpClient.postJson(URI.create(edcManagementUrl + "/v3/contractdefinitions"), contractDefinitionBody);
        ensureSuccessful("create contract definition", response.statusCode(), 200, 201, 409);
        return contractDefinitionId;
    }

    private static void ensureSuccessful(String action, int statusCode, int... allowedStatusCodes) throws IOException {
        for (int allowedStatusCode : allowedStatusCodes) {
            if (statusCode == allowedStatusCode) return;
        }
        throw new IOException(action + " failed with HTTP " + statusCode);
    }
}
