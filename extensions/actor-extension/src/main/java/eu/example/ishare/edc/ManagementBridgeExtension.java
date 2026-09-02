package eu.example.ishare.edc;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.Routers;
import akka.actor.typed.receptionist.Receptionist;
import eu.example.ishare.common.protocol.ManagementProtocol;
import org.eclipse.edc.connector.controlplane.services.spi.asset.AssetService;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractdefinition.ContractDefinitionService;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.policydefinition.PolicyDefinitionService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

@Extension(value = ManagementBridgeExtension.NAME)
public class ManagementBridgeExtension implements ServiceExtension {
    public static final String NAME = "iSHARE Akka Management Bridge";
    static final String ROLE_KEY = "ishare.management.bridge.role";

    @Inject private Monitor monitor;
    @Inject private ActorSystem<Void> akkaSystem;
    @Inject private AssetService assetService;
    @Inject private PolicyDefinitionService policyDefinitionService;
    @Inject private ContractDefinitionService contractDefinitionService;
    @Inject private CatalogService catalogService;
    @Inject private ContractNegotiationService contractNegotiationService;
    @Inject private TransferProcessService transferProcessService;

    @Override
    public String name() { return NAME; }

    @Override
    public void initialize(ServiceExtensionContext context) {
        String role = context.getSetting(ROLE_KEY, "none");
        int poolSize = context.getSetting("ishare.management.bridge.pool-size", 40);
        switch (role.toLowerCase()) {
            case "provider" -> {
                ActorRef<ManagementProtocol.ManagementCommand> providerRouter = akkaSystem.systemActorOf(
                        Routers.pool(poolSize, ProviderManagementActor.behavior(
                                assetService, policyDefinitionService, contractDefinitionService)),
                        "provider-management-actor", Props.empty());
                akkaSystem.receptionist().tell(Receptionist.register(ManagementProtocol.PROVIDER_KEY, providerRouter));
                monitor.info("ManagementBridgeExtension: provider-management-actor pool(" + poolSize + ") registered");
            }
            case "consumer" -> {
                ActorRef<ManagementProtocol.ManagementCommand> consumerRouter = akkaSystem.systemActorOf(
                        Routers.pool(poolSize, ConsumerManagementActor.behavior(
                                catalogService, contractNegotiationService, transferProcessService)),
                        "consumer-management-actor", Props.empty());
                akkaSystem.receptionist().tell(Receptionist.register(ManagementProtocol.CONSUMER_KEY, consumerRouter));
                monitor.info("ManagementBridgeExtension: consumer-management-actor pool(" + poolSize + ") registered");
            }
            default -> monitor.info("ManagementBridgeExtension: role=" + role + ", nothing registered on this node");
        }
    }
}
