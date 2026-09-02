package eu.example.ishare.apps.issuer;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.domain.vc.IssuerService;

import java.util.List;

import static eu.example.ishare.common.protocol.VcProtocol.*;

public final class ActorIssuerTransportHandler implements IssuerTransportHandler {
    private final String host;
    private final int port;
    private final List<String> seedNodes;

    public ActorIssuerTransportHandler(String host, int port, List<String> seedNodes) {
        this.host = host;
        this.port = port;
        this.seedNodes = seedNodes;
    }

    @Override
    public void start(IssuerService service) throws Exception {
        ActorSystem<IssuerCommand> system = ActorSystem.create(
                Actor.create(service),
                "ishare-cluster",
                ClusterBootstrap.buildConfig(host, port, seedNodes));

        System.out.printf("Issuer (akka transport) running as cluster node: %s%n", system.address());
        system.getWhenTerminated().toCompletableFuture().get();
    }

    private static final class Actor extends AbstractBehavior<IssuerCommand> {
        private final IssuerService service;

        static Behavior<IssuerCommand> create(IssuerService service) {
            return Behaviors.setup(ctx -> {
                ctx.getSystem().receptionist().tell(Receptionist.register(ISSUER_KEY, ctx.getSelf()));
                System.out.println("[issuer] registered with cluster Receptionist as '" + ISSUER_KEY + "'");
                return new Actor(ctx, service);
            });
        }

        private Actor(ActorContext<IssuerCommand> context, IssuerService service) {
            super(context);
            this.service = service;
        }

        @Override
        public Receive<IssuerCommand> createReceive() {
            return newReceiveBuilder()
                    .onMessage(IssueCredential.class, this::onIssueCredential)
                    .build();
        }

        private Behavior<IssuerCommand> onIssueCredential(IssueCredential cmd) {
            getContext().getLog().info("RECEIVED IssueCredential: subjectId={}", cmd.subjectId);
            IssuerService.CredentialResult result = service.issueCredential(cmd.subjectId, cmd.role);
            if (!result.isSuccess()) {
                getContext().getLog().warn("issueCredential failed for {}: {}", cmd.subjectId, result.error());
            }
            cmd.replyTo.tell(new CredentialResult(result.credentialJwt(), result.error()));
            return this;
        }
    }
}
