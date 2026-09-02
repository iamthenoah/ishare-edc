package eu.example.ishare.edc;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import eu.example.ishare.common.cluster.ClusterBootstrap;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

@Extension(value = ClusterNodeExtension.NAME)
public class ClusterNodeExtension implements ServiceExtension {
    public static final String NAME = "Akka Cluster Node";

    static final String AKKA_HOST_KEY = "ishare.akka.host";
    static final String AKKA_PORT_KEY = "ishare.akka.port";
    static final String AKKA_SEED_NODES_KEY = "ishare.akka.seed-nodes";

    @Inject private Monitor monitor;
    private ActorSystem<Void> system;

    @Override
    public String name() { return NAME; }

    @Provider
    public ActorSystem<Void> actorSystem(ServiceExtensionContext context) {
        String host = context.getSetting(AKKA_HOST_KEY, "127.0.0.1");
        int port = Integer.parseInt(context.getSetting(AKKA_PORT_KEY, "25251"));
        var seedNodes = ClusterBootstrap.parseSeedNodes(
                context.getSetting(AKKA_SEED_NODES_KEY, "akka://ishare-cluster@127.0.0.1:25251"));

        monitor.info("ClusterNodeExtension: starting " + host + ":" + port + " seedNodes=" + seedNodes);
        system = ActorSystem.create(Behaviors.empty(), "ishare-cluster", ClusterBootstrap.buildConfig(host, port, seedNodes));
        return system;
    }

    @Override
    public void shutdown() {
        if (system != null) system.terminate();
    }
}
