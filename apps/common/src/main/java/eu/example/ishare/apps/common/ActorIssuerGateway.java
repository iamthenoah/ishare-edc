package eu.example.ishare.apps.common;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import eu.example.ishare.common.protocol.VcProtocol;
import eu.example.ishare.common.protocol.VcProtocol.CredentialResult;
import eu.example.ishare.common.protocol.VcProtocol.IssueCredential;
import eu.example.ishare.common.protocol.VcProtocol.IssuerCommand;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ActorIssuerGateway implements eu.example.ishare.domain.vc.IssuerGateway, AutoCloseable {
    private final ActorSystem<Void> system;
    private final AtomicReference<ActorRef<IssuerCommand>> issuerRef = new AtomicReference<>();
    private final Duration askTimeout;
    private final Duration discoveryTimeout;

    private ActorIssuerGateway(ActorSystem<Void> system, Duration askTimeout, Duration discoveryTimeout) {
        this.system = system;
        this.askTimeout = askTimeout;
        this.discoveryTimeout = discoveryTimeout;
    }

    public static ActorIssuerGateway start(ActorSystem<Void> system, Duration askTimeout, Duration discoveryTimeout) {
        ActorIssuerGateway gateway = new ActorIssuerGateway(system, askTimeout, discoveryTimeout);
        gateway.subscribeToReceptionist();
        return gateway;
    }

    @Override
    public String requestCredential(String subjectId, String role) throws Exception {
        ActorRef<IssuerCommand> issuer = awaitIssuer();
        CompletionStage<CredentialResult> stage = AskPattern.ask(
                issuer, replyTo -> new IssueCredential(subjectId, role, replyTo), askTimeout, system.scheduler());
        CredentialResult result = stage.toCompletableFuture().get(askTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!result.isSuccess()) throw new Exception("IssueCredential failed: " + result.error);
        return result.credentialJwt;
    }

    private void subscribeToReceptionist() {
        var listener = system.systemActorOf(
                Behaviors.receive(Receptionist.Listing.class)
                        .onMessage(Receptionist.Listing.class, listing -> {
                            listing.getServiceInstances(VcProtocol.ISSUER_KEY).stream().findFirst().ifPresent(issuerRef::set);
                            return Behaviors.same();
                        })
                        .build(),
                "issuer-listing-listener", akka.actor.typed.Props.empty());
        system.receptionist().tell(Receptionist.subscribe(VcProtocol.ISSUER_KEY, listener));
    }

    private ActorRef<IssuerCommand> awaitIssuer() throws InterruptedException {
        long deadline = System.nanoTime() + discoveryTimeout.toNanos();
        while (issuerRef.get() == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("issuer actor not discovered within " + discoveryTimeout);
            }
            Thread.sleep(10);
        }
        return issuerRef.get();
    }

    @Override
    public void close() {
        system.terminate();
    }
}
