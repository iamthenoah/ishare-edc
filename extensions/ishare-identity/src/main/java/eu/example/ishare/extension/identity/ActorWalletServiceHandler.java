package eu.example.ishare.extension.identity;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import eu.example.ishare.common.protocol.VcProtocol;
import eu.example.ishare.common.protocol.VcProtocol.CreatePresentation;
import eu.example.ishare.common.protocol.VcProtocol.PresentationResult;
import eu.example.ishare.common.protocol.VcProtocol.WalletCommand;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class ActorWalletServiceHandler implements WalletServiceHandler {
    private final ActorSystem<Void> system;
    private final AtomicReference<ActorRef<WalletCommand>> walletRef = new AtomicReference<>();
    private final Duration askTimeout;
    private final Duration discoveryTimeout;
    private final Consumer<String> log;

    ActorWalletServiceHandler(ActorSystem<Void> system, String partyId, Duration askTimeout,
                              Duration discoveryTimeout, Consumer<String> log) {
        this.system = system;
        this.askTimeout = askTimeout;
        this.discoveryTimeout = discoveryTimeout;
        this.log = log;
        subscribeToReceptionist(partyId);
    }

    @Override
    public String requestPresentation(String audience) throws Exception {
        var wallet = awaitWalletRef();
        CompletionStage<PresentationResult> stage = AskPattern.ask(
                wallet, replyTo -> new CreatePresentation(audience, replyTo), askTimeout, system.scheduler());
        var response = stage.toCompletableFuture().get(askTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!response.isSuccess()) throw new Exception("Wallet rejected presentation request: " + response.error);
        return response.presentationJwt;
    }

    private ActorRef<WalletCommand> awaitWalletRef() throws Exception {
        long deadline = System.nanoTime() + discoveryTimeout.toNanos();
        while (walletRef.get() == null) {
            if (System.nanoTime() > deadline) {
                throw new Exception("wallet actor not discovered on the cluster within " + discoveryTimeout);
            }
            Thread.sleep(10);
        }
        return walletRef.get();
    }

    private void subscribeToReceptionist(String partyId) {
        var key = VcProtocol.walletKey(partyId);
        Behavior<Receptionist.Listing> behavior = Behaviors.receive(Receptionist.Listing.class)
                .onMessage(Receptionist.Listing.class, listing -> {
                    var instances = listing.getServiceInstances(key);
                    log.accept("receptionist listing for wallet-" + partyId + ": " + instances.size() + " instance(s)");
                    instances.stream().findFirst().ifPresent(ref -> {
                        log.accept("wallet actor discovered: " + ref);
                        walletRef.set(ref);
                    });
                    return Behaviors.same();
                })
                .build();
        var listener = system.systemActorOf(behavior, "wallet-handler-listing-listener", Props.empty());
        system.receptionist().tell(Receptionist.subscribe(key, listener));
    }
}
