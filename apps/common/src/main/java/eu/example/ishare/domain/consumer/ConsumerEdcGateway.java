package eu.example.ishare.domain.consumer;

public interface ConsumerEdcGateway {
    void ping() throws Exception;

    CatalogOffer requestCatalog(String counterPartyAddress, String counterPartyId) throws Exception;

    String startNegotiation(String counterPartyAddress, String counterPartyId, String offerId, String assetId) throws Exception;

    NegotiationState pollNegotiation(String negotiationId) throws Exception;

    String startTransfer(String counterPartyAddress, String counterPartyId, String contractId, String assetId,
                         String transferType, String dataDestinationType, String dataDestinationBaseUrl) throws Exception;

    TransferState pollTransfer(String transferId) throws Exception;
}
