package eu.example.ishare.apps.common;

import com.fasterxml.jackson.databind.JsonNode;
import eu.example.ishare.domain.consumer.CatalogOffer;
import eu.example.ishare.domain.consumer.ConsumerEdcGateway;
import eu.example.ishare.domain.consumer.NegotiationState;
import eu.example.ishare.domain.consumer.TransferState;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

public final class HttpConsumerEdcGateway implements ConsumerEdcGateway {
    private final URI edcManagementUrl;
    private final URI benchUrl;
    private final EdcHttpClient httpClient;

    public HttpConsumerEdcGateway(URI edcManagementUrl, URI benchUrl, EdcHttpClient httpClient) {
        this.edcManagementUrl = edcManagementUrl;
        this.benchUrl = benchUrl;
        this.httpClient = httpClient;
    }

    @Override
    public void ping() throws Exception {
        httpClient.get(benchUrl);
    }

    @Override
    public CatalogOffer requestCatalog(String counterPartyAddress, String counterPartyId) throws Exception {
        JsonNode catalog = httpClient.postJsonAsJson(
                URI.create(edcManagementUrl + "/v3/catalog/request"),
                Map.of(
                        "@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                        "counterPartyAddress", counterPartyAddress,
                        "counterPartyId", counterPartyId,
                        "protocol", "dataspace-protocol-http"
                )
        );
        return extractOffer(catalog);
    }

    @Override
    public String startNegotiation(String counterPartyAddress, String counterPartyId, String offerId, String assetId) throws Exception {
        JsonNode negotiation = httpClient.postJsonAsJson(
                URI.create(edcManagementUrl + "/v3/contractnegotiations"),
                Map.of(
                        "@context", Map.of(
                                "@vocab", "https://w3id.org/edc/v0.0.1/ns/",
                                "odrl", "http://www.w3.org/ns/odrl/2/"
                        ),
                        "counterPartyAddress", counterPartyAddress,
                        "protocol", "dataspace-protocol-http",
                        "policy", Map.of(
                                "@id", offerId,
                                "@type", "odrl:Offer",
                                "odrl:assigner", Map.of("@id", counterPartyId),
                                "odrl:target", Map.of("@id", assetId)
                        )
                )
        );
        return negotiation.path("@id").asText();
    }

    @Override
    public NegotiationState pollNegotiation(String negotiationId) throws Exception {
        JsonNode stateResponse = httpClient.getJson(URI.create(edcManagementUrl + "/v3/contractnegotiations/" + negotiationId));
        String state = stateResponse.path("state").asText();
        String agreementId = "FINALIZED".equalsIgnoreCase(state) ? stateResponse.path("contractAgreementId").asText() : null;
        return new NegotiationState(state, agreementId, null);
    }

    @Override
    public String startTransfer(String counterPartyAddress, String counterPartyId, String contractId, String assetId,
                                 String transferType, String dataDestinationType, String dataDestinationBaseUrl) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"));
        body.put("counterPartyAddress", counterPartyAddress);
        body.put("protocol", "dataspace-protocol-http");
        body.put("contractId", contractId);
        body.put("assetId", assetId);
        body.put("transferType", transferType);

        if (dataDestinationType != null && !dataDestinationType.isBlank()) {
            body.put("dataDestination", Map.of("type", dataDestinationType, "baseUrl", dataDestinationBaseUrl));
        }
        JsonNode transfer = httpClient.postJsonAsJson(URI.create(edcManagementUrl + "/v3/transferprocesses"), body);
        return transfer.path("@id").asText();
    }

    @Override
    public TransferState pollTransfer(String transferId) throws Exception {
        JsonNode stateResponse = httpClient.getJson(URI.create(edcManagementUrl + "/v3/transferprocesses/" + transferId));
        return new TransferState(stateResponse.path("state").asText(), null);
    }

    private static CatalogOffer extractOffer(JsonNode catalog) throws IOException {
        JsonNode datasets = catalog.path("dcat:dataset");
        JsonNode dataset = datasets.isArray() ? datasets.path(0) : datasets;

        if (dataset.isMissingNode() || dataset.isNull()) {
            throw new IOException("Catalog response did not contain a dataset");
        }
        JsonNode policy = dataset.path("odrl:hasPolicy");

        if (policy.isArray() && !policy.isEmpty()) {
            policy = policy.get(0);
        }
        String assetId = dataset.path("@id").asText();
        String offerId = policy.path("@id").asText();

        if (assetId.isBlank() || offerId.isBlank()) {
            throw new IOException("Catalog response did not contain a usable offer");
        }
        return new CatalogOffer(assetId, offerId);
    }
}
