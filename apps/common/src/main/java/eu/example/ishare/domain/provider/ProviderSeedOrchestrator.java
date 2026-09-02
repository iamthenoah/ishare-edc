package eu.example.ishare.domain.provider;

import java.time.Instant;

public final class ProviderSeedOrchestrator {
    private ProviderSeedOrchestrator() {
    }

    public static SeedResult seed(ProviderEdcGateway gateway, SeedRequest request) throws Exception {
        String createdAssetId = gateway.createAsset(
                request.assetId(), request.assetName(), request.description(), request.contentUrl());
        String createdPolicyId = gateway.createPolicy(request.policyId());
        String createdContractDefinitionId = gateway.createContractDefinition(
                request.contractDefinitionId(), request.policyId());

        return new SeedResult(
                request.assetId(), request.assetName(), request.description(), request.contentUrl(),
                request.policyId(), request.contractDefinitionId(),
                createdAssetId, createdPolicyId, createdContractDefinitionId,
                Instant.now().toString());
    }

    public record SeedRequest(String assetId, String assetName, String description, String contentUrl,
                               String policyId, String contractDefinitionId) {
    }

    public record SeedResult(String assetId, String assetName, String description, String contentUrl,
                              String policyId, String contractDefinitionId,
                              String createdAssetId, String createdPolicyId, String createdContractDefinitionId,
                              String seededAt) {
    }
}
