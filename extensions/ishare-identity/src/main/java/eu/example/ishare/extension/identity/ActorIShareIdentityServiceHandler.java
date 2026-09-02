package eu.example.ishare.extension.identity;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import akka.cluster.ClusterEvent;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Subscribe;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.example.ishare.common.protocol.ArProtocol;
import eu.example.ishare.common.protocol.ArProtocol.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class ActorIShareIdentityServiceHandler implements IShareIdentityServiceHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorSystem<Void> system;
    private final AtomicReference<ActorRef<ArCommand>> arRef = new AtomicReference<>();
    private final Duration askTimeout;
    private final Duration discoveryTimeout;
    private final Consumer<String> log;

    ActorIShareIdentityServiceHandler(ActorSystem<Void> system, Duration askTimeout, Duration discoveryTimeout, Consumer<String> log) {
        this.system = system;
        this.askTimeout = askTimeout;
        this.discoveryTimeout = discoveryTimeout;
        this.log = log;
        subscribeToReceptionist();
        subscribeToClusterEvents();
    }

    @Override
    public String requestArToken(String clientId, String clientAssertion) throws Exception {
        var target = awaitArRef();
        CompletionStage<TokenResponse> stage = AskPattern.ask(
                target, replyTo -> new IssueToken(clientId, clientAssertion, replyTo), askTimeout, system.scheduler());
        var response = stage.toCompletableFuture().get(askTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!response.isSuccess()) throw new Exception("AR rejected token request: " + response.error);
        return response.accessToken;
    }

    @Override
    public String requestDelegation(String arToken, String policyIssuer, String accessSubject) throws Exception {
        String maskJson = MAPPER.writeValueAsString(Map.of(
                "policyIssuer", policyIssuer, "target", Map.of("accessSubject", accessSubject)));
        var target = awaitArRef();
        CompletionStage<DelegationResponse> stage = AskPattern.ask(
                target, replyTo -> new RequestDelegation(arToken, maskJson, replyTo), askTimeout, system.scheduler());
        var response = stage.toCompletableFuture().get(askTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!response.isSuccess()) throw new Exception("AR rejected delegation request: " + response.error);
        return response.delegationToken;
    }

    private ActorRef<ArCommand> awaitArRef() throws Exception {
        long deadline = System.nanoTime() + discoveryTimeout.toNanos();
        while (arRef.get() == null) {
            if (System.nanoTime() > deadline) {
                throw new Exception("AR actor not discovered on the cluster within " + discoveryTimeout);
            }
            Thread.sleep(10);
        }
        return arRef.get();
    }

    private void subscribeToClusterEvents() {
        Behavior<ClusterEvent.MemberEvent> behavior = Behaviors.receive(ClusterEvent.MemberEvent.class)
                .onMessage(ClusterEvent.MemberEvent.class, event -> { log.accept("cluster member event: " + event); return Behaviors.same(); })
                .build();
        var listener = system.systemActorOf(behavior, "ar-handler-cluster-member-listener", Props.empty());
        Cluster.get(system).subscriptions().tell(Subscribe.create(listener, ClusterEvent.MemberEvent.class));
    }

    private void subscribeToReceptionist() {
        Behavior<Receptionist.Listing> behavior = Behaviors.receive(Receptionist.Listing.class)
                .onMessage(Receptionist.Listing.class, listing -> {
                    var instances = listing.getServiceInstances(ArProtocol.SERVICE_KEY);
                    log.accept("receptionist listing for ar-actor-service: " + instances.size() + " instance(s)");
                    instances.stream().findFirst().ifPresent(ref -> { log.accept("AR actor discovered: " + ref); arRef.set(ref); });
                    return Behaviors.same();
                })
                .build();
        var listener = system.systemActorOf(behavior, "ar-handler-listing-listener", Props.empty());
        system.receptionist().tell(Receptionist.subscribe(ArProtocol.SERVICE_KEY, listener));
    }
}
