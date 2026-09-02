package eu.example.ishare.apps.common;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import eu.example.ishare.common.protocol.ManagementProtocol;
import eu.example.ishare.common.protocol.ManagementProtocol.*;
import eu.example.ishare.domain.consumer.CatalogOffer;
import eu.example.ishare.domain.consumer.ConsumerEdcGateway;
import eu.example.ishare.domain.consumer.NegotiationState;
import eu.example.ishare.domain.consumer.TransferState;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class ActorConsumerEdcGateway implements ConsumerEdcGateway, ManagementActorClient {
    private final ActorSystem<Void> system;
    private final AtomicReference<ActorRef<ManagementCommand>> targetRef = new AtomicReference<>();
    private final Duration askTimeout;
    private final Duration discoveryTimeout;

    private ActorConsumerEdcGateway(ActorSystem<Void> system, Duration askTimeout, Duration discoveryTimeout) {
        this.system = system;
        this.askTimeout = askTimeout;
        this.discoveryTimeout = discoveryTimeout;
    }

    public static ActorConsumerEdcGateway start(String host, int port, List<String> seedNodes, Duration askTimeout, Duration discoveryTimeout) {
        ActorConsumerEdcGateway client = new ActorConsumerEdcGateway(
                ManagementActorClient.startClusterClient(host, port, seedNodes), askTimeout, discoveryTimeout);
        client.subscribeToReceptionist(ManagementProtocol.CONSUMER_KEY);
        return client;
    }

    @Override
    public ActorSystem<Void> system() {
        return system;
    }

    @Override
    public AtomicReference<ActorRef<ManagementCommand>> targetRef() {
        return targetRef;
    }

    @Override
    public Duration askTimeout() {
        return askTimeout;
    }

    @Override
    public Duration discoveryTimeout() {
        return discoveryTimeout;
    }

    @Override
    public void ping() throws Exception {
        ask((ActorRef<ManagementProtocol.Pong> replyTo) -> new ManagementProtocol.Ping(replyTo));
    }

    @Override
    public CatalogOffer requestCatalog(String counterPartyAddress, String counterPartyId) throws Exception {
        CatalogResult result = ask(replyTo -> new RequestCatalog(counterPartyAddress, counterPartyId, replyTo));
        if (result.error != null) throw new Exception("RequestCatalog failed: " + result.error);
        if (result.offerId == null || result.offerId.isBlank()) throw new Exception("RequestCatalog returned blank offerId");
        return new CatalogOffer(result.assetId, result.offerId);
    }

    @Override
    public String startNegotiation(String counterPartyAddress, String counterPartyId, String offerId, String assetId) throws Exception {
        OperationResult result = ask(replyTo -> new StartNegotiation(counterPartyAddress, counterPartyId, offerId, assetId, replyTo));
        if (!result.success) throw new Exception("StartNegotiation failed: " + result.error);
        return result.id;
    }

    @Override
    public NegotiationState pollNegotiation(String negotiationId) throws Exception {
        NegotiationStateResult result = ask(replyTo -> new PollNegotiation(negotiationId, replyTo));
        return new NegotiationState(result.state, result.agreementId, result.error);
    }

    @Override
    public String startTransfer(String counterPartyAddress, String counterPartyId, String contractId, String assetId,
                                 String transferType, String dataDestinationType, String dataDestinationBaseUrl) throws Exception {
        OperationResult result = ask(replyTo -> new StartTransfer(
                counterPartyAddress, counterPartyId, contractId, assetId, transferType, dataDestinationType, dataDestinationBaseUrl, replyTo));
        if (!result.success) throw new Exception("StartTransfer failed: " + result.error);
        return result.id;
    }

    @Override
    public TransferState pollTransfer(String transferId) throws Exception {
        TransferStateResult result = ask(replyTo -> new PollTransfer(transferId, replyTo));
        return new TransferState(result.state, result.error);
    }
}
