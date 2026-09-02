package eu.example.ishare.domain.provider;

public interface ProviderEdcGateway {
    String createAsset(String assetId, String name, String description, String contentUrl) throws Exception;

    String createPolicy(String policyId) throws Exception;

    String createContractDefinition(String contractDefinitionId, String policyId) throws Exception;
}
