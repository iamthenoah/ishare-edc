package eu.example.ishare.apps.common;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import eu.example.ishare.common.protocol.ManagementProtocol;
import eu.example.ishare.common.protocol.ManagementProtocol.*;
import eu.example.ishare.domain.provider.ProviderEdcGateway;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class ActorProviderEdcGateway implements ProviderEdcGateway, ManagementActorClient {
    private final ActorSystem<Void> system;
    private final AtomicReference<ActorRef<ManagementCommand>> targetRef = new AtomicReference<>();
    private final Duration askTimeout;
    private final Duration discoveryTimeout;

    private ActorProviderEdcGateway(ActorSystem<Void> system, Duration askTimeout, Duration discoveryTimeout) {
        this.system = system;
        this.askTimeout = askTimeout;
        this.discoveryTimeout = discoveryTimeout;
    }

    public static ActorProviderEdcGateway start(String host, int port, List<String> seedNodes, Duration askTimeout, Duration discoveryTimeout) {
        ActorProviderEdcGateway client = new ActorProviderEdcGateway(
                ManagementActorClient.startClusterClient(host, port, seedNodes), askTimeout, discoveryTimeout);
        client.subscribeToReceptionist(ManagementProtocol.PROVIDER_KEY);
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
    public String createAsset(String assetId, String name, String description, String contentUrl) throws Exception {
        OperationResult result = ask(replyTo -> new CreateAsset(assetId, name, description, contentUrl, replyTo));
        if (!result.success) throw new Exception("CreateAsset failed: " + result.error);
        return result.id;
    }

    @Override
    public String createPolicy(String policyId) throws Exception {
        OperationResult result = ask(replyTo -> new CreatePolicy(policyId, replyTo));
        if (!result.success) throw new Exception("CreatePolicy failed: " + result.error);
        return result.id;
    }

    @Override
    public String createContractDefinition(String contractDefinitionId, String policyId) throws Exception {
        OperationResult result = ask(replyTo -> new CreateContractDefinition(contractDefinitionId, policyId, replyTo));
        if (!result.success) throw new Exception("CreateContractDefinition failed: " + result.error);
        return result.id;
    }
}
