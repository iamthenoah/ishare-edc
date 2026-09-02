package eu.example.ishare.apps.common;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.common.protocol.ManagementProtocol.ManagementCommand;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public interface ManagementActorClient extends AutoCloseable {
    ActorSystem<Void> system();

    AtomicReference<ActorRef<ManagementCommand>> targetRef();

    Duration askTimeout();

    Duration discoveryTimeout();

    default void subscribeToReceptionist(ServiceKey<ManagementCommand> key) {
        var listener = system().systemActorOf(
                Behaviors.receive(Receptionist.Listing.class)
                        .onMessage(Receptionist.Listing.class, listing -> {
                            listing.getServiceInstances(key).stream().findFirst().ifPresent(targetRef()::set);
                            return Behaviors.same();
                        })
                        .build(),
                "management-listing-listener", akka.actor.typed.Props.empty());
        system().receptionist().tell(Receptionist.subscribe(key, listener));
    }

    default ActorRef<ManagementCommand> awaitTarget() {
        long deadline = System.nanoTime() + discoveryTimeout().toNanos();
        while (targetRef().get() == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("management actor not discovered within " + discoveryTimeout());
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for management actor discovery", e);
            }
        }
        return targetRef().get();
    }

    default <R> R ask(akka.japi.function.Function<ActorRef<R>, ManagementCommand> messageFactory) throws Exception {
        ActorRef<ManagementCommand> target = awaitTarget();
        CompletionStage<R> stage = AskPattern.ask(target, messageFactory, askTimeout(), system().scheduler());
        return stage.toCompletableFuture().get(askTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    static ActorSystem<Void> startClusterClient(String host, int port, List<String> seedNodes) {
        return ActorSystem.create(Behaviors.empty(), "ishare-cluster", ClusterBootstrap.buildConfig(host, port, seedNodes));
    }

    @Override
    default void close() {
        system().terminate();
    }
}
