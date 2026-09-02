package eu.example.ishare.apps.wallet;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.common.protocol.VcProtocol;
import eu.example.ishare.domain.vc.WalletService;

import java.util.List;

import static eu.example.ishare.common.protocol.VcProtocol.*;

public final class ActorWalletTransportHandler implements WalletTransportHandler {
    private final String partyId;
    private final String host;
    private final int port;
    private final List<String> seedNodes;

    public ActorWalletTransportHandler(String partyId, String host, int port, List<String> seedNodes) {
        this.partyId = partyId;
        this.host = host;
        this.port = port;
        this.seedNodes = seedNodes;
    }

    @Override
    public void start(WalletService service) throws Exception {
        ActorSystem<WalletCommand> system = ActorSystem.create(
                Actor.create(partyId, service),
                "ishare-cluster",
                ClusterBootstrap.buildConfig(host, port, seedNodes));

        System.out.printf("Wallet for %s (akka transport) running as cluster node: %s%n", partyId, system.address());
        system.getWhenTerminated().toCompletableFuture().get();
    }

    private static final class Actor extends AbstractBehavior<WalletCommand> {
        private final WalletService service;

        static Behavior<WalletCommand> create(String partyId, WalletService service) {
            return Behaviors.setup(ctx -> {
                ctx.getSystem().receptionist().tell(Receptionist.register(VcProtocol.walletKey(partyId), ctx.getSelf()));
                System.out.println("[wallet] registered with cluster Receptionist as 'wallet-" + partyId + "'");
                return new Actor(ctx, service);
            });
        }

        private Actor(ActorContext<WalletCommand> context, WalletService service) {
            super(context);
            this.service = service;
        }

        @Override
        public Receive<WalletCommand> createReceive() {
            return newReceiveBuilder()
                    .onMessage(CreatePresentation.class, this::onCreatePresentation)
                    .build();
        }

        private Behavior<WalletCommand> onCreatePresentation(CreatePresentation cmd) {
            WalletService.PresentationResult result = service.createPresentation(cmd.audience);
            if (!result.isSuccess()) {
                getContext().getLog().warn("createPresentation failed: {}", result.error());
            }
            cmd.replyTo.tell(new PresentationResult(result.presentationJwt(), result.error()));
            return this;
        }
    }
}
