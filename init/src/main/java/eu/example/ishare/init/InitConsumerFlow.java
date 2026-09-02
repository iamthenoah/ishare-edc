package eu.example.ishare.init;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.example.ishare.apps.common.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

final class InitConsumerFlow {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String PROVIDER_EORI = "EU.EORI.NLTESTPROVIDER";
    private static final String CONSUMER_EORI = "EU.EORI.NLTESTCONSUMER";

    private InitConsumerFlow() {}

    static void run() throws Exception {
        String consumerMgmt = AppConfig.string("consumer.management-url", "http://localhost:29193/management");
        String providerDsp = AppConfig.string("consumer.provider.protocol-url", "http://localhost:19194/protocol");
        String pushUrl = AppConfig.string("consumer.flow.push-url", "http://localhost:7002/receive");

        System.out.println("=== Consumer Flow ===");
        System.out.println("Consumer: " + CONSUMER_EORI);
        System.out.println("Provider: " + PROVIDER_EORI);

        String[] offer = catalog(consumerMgmt, providerDsp);
        String assetId = offer[0];
        String offerId = offer[1];

        String agreementId = negotiate(consumerMgmt, providerDsp, assetId, offerId);
        transfer(consumerMgmt, providerDsp, assetId, agreementId, pushUrl);

        System.out.println("\nFlow complete");
        System.out.println("  Catalog:     OK");
        System.out.println("  Negotiation: OK");
        System.out.println("  Transfer:    OK");
        System.out.println("  Data:        pushed to " + pushUrl
                + " - check the Consumer App console for \"[consumer] received data push\"");
    }

    private static String[] catalog(String consumerMgmt, String providerDsp) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                "@type", "CatalogRequest",
                "counterPartyAddress", providerDsp,
                "counterPartyId", PROVIDER_EORI,
                "protocol", "dataspace-protocol-http"));
        JsonNode response = postJson(consumerMgmt + "/v3/catalog/request", body);

        JsonNode datasetNode = response.get("dcat:dataset");
        JsonNode dataset = datasetNode != null && datasetNode.isArray() ? datasetNode.get(0) : datasetNode;
        if (dataset == null) {
            throw new IllegalStateException("No datasets in catalog - run :init:initProvider first");
        }
        JsonNode policy = dataset.get("odrl:hasPolicy");
        JsonNode policyNode = policy != null && policy.isArray() ? policy.get(0) : policy;
        String offerId = policyNode == null ? null : text(policyNode.get("@id"));
        String assetId = text(dataset.get("@id"));

        log("CATALOG", "assetId=" + assetId + " offerId=" + offerId);
        if (assetId == null || offerId == null) {
            throw new IllegalStateException("No datasets in catalog - run :init:initProvider first");
        }
        return new String[] {assetId, offerId};
    }

    private static String negotiate(String consumerMgmt, String providerDsp, String assetId, String offerId) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                "@type", "ContractRequest",
                "counterPartyAddress", providerDsp,
                "counterPartyId", PROVIDER_EORI,
                "protocol", "dataspace-protocol-http",
                "policy", Map.of(
                        "@context", "http://www.w3.org/ns/odrl.jsonld",
                        "@type", "odrl:Offer",
                        "@id", offerId,
                        "assigner", PROVIDER_EORI,
                        "target", assetId)));
        JsonNode started = postJson(consumerMgmt + "/v3/contractnegotiations", body);
        String negotiationId = text(started.get("@id"));
        log("NEGOTIATION STARTED", "negotiationId=" + negotiationId);

        long deadline = System.nanoTime() + 30_000_000_000L;
        String lastLoggedState = null;
        int observed = 0;
        while (System.nanoTime() < deadline) {
            JsonNode status = getJson(consumerMgmt + "/v3/contractnegotiations/" + negotiationId);
            String state = text(status.get("state"));
            if (!state.equals(lastLoggedState)) {
                observed++;
                log("NEGOTIATION STATE (" + observed + ")", "state=" + state);
                lastLoggedState = state;
            }

            if ("FINALIZED".equals(state)) {
                String agreementId = text(status.get("contractAgreementId"));
                log("NEGOTIATION FINALIZED", "agreementId=" + agreementId);
                return agreementId;
            }
            if ("TERMINATED".equals(state)) {
                throw new IllegalStateException("Negotiation terminated");
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Negotiation did not finalize in time");
    }

    private static String transfer(String consumerMgmt, String providerDsp, String assetId, String agreementId, String pushUrl) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "@context", Map.of("@vocab", "https://w3id.org/edc/v0.0.1/ns/"),
                "@type", "TransferRequest",
                "counterPartyAddress", providerDsp,
                "counterPartyId", PROVIDER_EORI,
                "contractId", agreementId,
                "assetId", assetId,
                "protocol", "dataspace-protocol-http",
                "transferType", "HttpData-PUSH",
                "dataDestination", Map.of("type", "HttpData", "baseUrl", pushUrl)));
        JsonNode started = postJson(consumerMgmt + "/v3/transferprocesses", body);
        String transferId = text(started.get("@id"));
        log("TRANSFER STARTED", "transferId=" + transferId);

        long deadline = System.nanoTime() + 30_000_000_000L;
        String lastLoggedState = null;
        int observed = 0;
        while (System.nanoTime() < deadline) {
            JsonNode status = getJson(consumerMgmt + "/v3/transferprocesses/" + transferId);
            String state = text(status.get("state"));
            if (!state.equals(lastLoggedState)) {
                observed++;
                log("TRANSFER STATE (" + observed + ")", "state=" + state);
                lastLoggedState = state;
            }

            if ("STARTED".equals(state)) {
                return transferId;
            }
            if ("TERMINATED".equals(state)) {
                throw new IllegalStateException("Transfer terminated - is the dataplane registered? Run :init:initProvider first");
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Transfer did not start in time");
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static JsonNode postJson(String url, String body) throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(resp.body());
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(resp.body());
    }

    private static void log(String label, String data) {
        System.out.println("\n-- " + label + " " + "-".repeat(Math.max(0, 55 - label.length())));
        System.out.println(data);
    }
}
