package eu.example.ishare.apps.ar;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.cluster.ClusterEvent;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Subscribe;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import eu.example.ishare.domain.ar.ArService;

import java.util.List;

import static eu.example.ishare.common.protocol.ArProtocol.*;

public final class ActorArTransportHandler implements ArTransportHandler {
    private final String host;
    private final int port;
    private final List<String> seedNodes;

    public ActorArTransportHandler(String host, int port, List<String> seedNodes) {
        this.host = host;
        this.port = port;
        this.seedNodes = seedNodes;
    }

    @Override
    public void start(ArService service) throws Exception {
        ActorSystem<ArCommand> system = ActorSystem.create(
                Actor.create(service),
                "ishare-cluster",
                ClusterBootstrap.buildConfig(host, port, seedNodes));

        System.out.printf("AR (akka transport) running as cluster node: %s%n", system.address());
        system.getWhenTerminated().toCompletableFuture().get();
    }

    private static final class Actor extends AbstractBehavior<ArCommand> {
        private final ArService service;

        static Behavior<ArCommand> create(ArService service) {
            return Behaviors.setup(ctx -> {
                ctx.getSystem().receptionist().tell(Receptionist.register(SERVICE_KEY, ctx.getSelf()));
                System.out.println("[ar] registered with cluster Receptionist as '" + SERVICE_KEY + "'");

                ActorRef<ClusterEvent.MemberEvent> memberListener = ctx.getSystem().systemActorOf(
                        Behaviors.receive(ClusterEvent.MemberEvent.class)
                                .onMessage(ClusterEvent.MemberEvent.class, event -> {
                                    System.out.println("[ar] cluster member event: " + event);
                                    return Behaviors.same();
                                }).build(),
                        "cluster-member-listener", akka.actor.typed.Props.empty());
                Cluster.get(ctx.getSystem()).subscriptions().tell(Subscribe.create(memberListener, ClusterEvent.MemberEvent.class));

                return new Actor(ctx, service);
            });
        }

        private Actor(ActorContext<ArCommand> context, ArService service) {
            super(context);
            this.service = service;
        }

        @Override
        public Receive<ArCommand> createReceive() {
            return newReceiveBuilder()
                    .onMessage(IssueToken.class, this::onIssueToken)
                    .onMessage(RequestDelegation.class, this::onRequestDelegation)
                    .onMessage(AddPolicy.class, this::onAddPolicy)
                    .onMessage(GetPolicies.class, this::onGetPolicies)
                    .onMessage(GetPolicy.class, this::onGetPolicy)
                    .onMessage(DeletePolicy.class, this::onDeletePolicy)
                    .build();
        }

        private Behavior<ArCommand> onIssueToken(IssueToken cmd) {
            getContext().getLog().info("RECEIVED IssueToken: clientId={}", cmd.clientId);
            ArService.TokenResult result = service.issueToken(cmd.clientId, cmd.clientAssertion);
            if (!result.isSuccess()) {
                getContext().getLog().warn("issueToken failed for {}: {}", cmd.clientId, result.error());
            }
            cmd.replyTo.tell(new TokenResponse(result.accessToken(), result.error()));
            return this;
        }

        private Behavior<ArCommand> onRequestDelegation(RequestDelegation cmd) {
            getContext().getLog().info("RECEIVED RequestDelegation");
            ArService.DelegationResult result = service.requestDelegation(cmd.accessToken, cmd.delegationRequestJson);
            if (!result.isSuccess()) {
                getContext().getLog().warn("requestDelegation failed: {}", result.error());
            }
            cmd.replyTo.tell(new DelegationResponse(result.delegationToken(), result.error()));
            return this;
        }

        private Behavior<ArCommand> onAddPolicy(AddPolicy cmd) {
            ArService.PolicyResult result = service.addPolicy(cmd.createdBy, cmd.policyJson);
            cmd.replyTo.tell(new PolicyResult(result.success(), result.jsonPayload(), result.error()));
            return this;
        }

        private Behavior<ArCommand> onGetPolicies(GetPolicies cmd) {
            ArService.PolicyResult result = service.getPolicies();
            cmd.replyTo.tell(new PolicyResult(result.success(), result.jsonPayload(), result.error()));
            return this;
        }

        private Behavior<ArCommand> onGetPolicy(GetPolicy cmd) {
            ArService.PolicyResult result = service.getPolicy(cmd.id);
            cmd.replyTo.tell(new PolicyResult(result.success(), result.jsonPayload(), result.error()));
            return this;
        }

        private Behavior<ArCommand> onDeletePolicy(DeletePolicy cmd) {
            ArService.PolicyResult result = service.deletePolicy(cmd.id);
            cmd.replyTo.tell(new PolicyResult(result.success(), result.jsonPayload(), result.error()));
            return this;
        }
    }
}
