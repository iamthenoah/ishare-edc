package eu.example.ishare.edc;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractDefinition;
import org.eclipse.edc.connector.controlplane.policy.spi.PolicyDefinition;
import org.eclipse.edc.connector.controlplane.services.spi.asset.AssetService;
import org.eclipse.edc.connector.controlplane.services.spi.contractdefinition.ContractDefinitionService;
import org.eclipse.edc.connector.controlplane.services.spi.policydefinition.PolicyDefinitionService;
import org.eclipse.edc.connector.dataplane.http.spi.HttpDataAddress;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.result.ServiceResult;

import java.util.List;

import static eu.example.ishare.common.protocol.ManagementProtocol.*;

public final class ProviderManagementActor extends AbstractBehavior<ManagementCommand> {
    private final AssetService assetService;
    private final PolicyDefinitionService policyDefinitionService;
    private final ContractDefinitionService contractDefinitionService;

    private ProviderManagementActor(
            ActorContext<ManagementCommand> ctx,
            AssetService assetService,
            PolicyDefinitionService policyDefinitionService,
            ContractDefinitionService contractDefinitionService
    ) {
        super(ctx);
        this.assetService = assetService;
        this.policyDefinitionService = policyDefinitionService;
        this.contractDefinitionService = contractDefinitionService;
    }

    @Override
    public Receive<ManagementCommand> createReceive() {
        return newReceiveBuilder()
                .onMessage(CreateAsset.class, this::onCreateAsset)
                .onMessage(CreatePolicy.class, this::onCreatePolicy)
                .onMessage(CreateContractDefinition.class, this::onCreateContractDefinition)
                .build();
    }

    private Behavior<ManagementCommand> onCreateAsset(CreateAsset cmd) {
        try {
            HttpDataAddress dataAddress = HttpDataAddress.Builder.newInstance()
                    .baseUrl(cmd.contentUrl)
                    .proxyPath("false")
                    .proxyQueryParams("false")
                    .build();

            Asset asset = Asset.Builder.newInstance()
                    .id(cmd.assetId)
                    .name(cmd.name)
                    .property("description", cmd.description)
                    .property("contenttype", "application/json")
                    .dataAddress(dataAddress)
                    .build();

            ServiceResult<Asset> result = assetService.create(asset);

            if (result.succeeded() || isAlreadyExists(result.getFailureDetail())) {
                cmd.replyTo.tell(new OperationResult(true, cmd.assetId, null));
            } else {
                cmd.replyTo.tell(new OperationResult(false, null, result.getFailureDetail()));
            }
        } catch (Exception e) {
            getContext().getLog().warn("CreateAsset failed: {}", e.getMessage());
            cmd.replyTo.tell(new OperationResult(false, null, e.getMessage()));
        }
        return this;
    }

    private Behavior<ManagementCommand> onCreatePolicy(CreatePolicy cmd) {
        try {
            Policy policy = Policy.Builder.newInstance().build();

            PolicyDefinition policyDefinition = PolicyDefinition.Builder.newInstance()
                    .id(cmd.policyId)
                    .policy(policy)
                    .build();

            ServiceResult<PolicyDefinition> result = policyDefinitionService.create(policyDefinition);

            if (result.succeeded() || isAlreadyExists(result.getFailureDetail())) {
                cmd.replyTo.tell(new OperationResult(true, cmd.policyId, null));
            } else {
                cmd.replyTo.tell(new OperationResult(false, null, result.getFailureDetail()));
            }
        } catch (Exception e) {
            getContext().getLog().warn("CreatePolicy failed: {}", e.getMessage());
            cmd.replyTo.tell(new OperationResult(false, null, e.getMessage()));
        }
        return this;
    }

    private Behavior<ManagementCommand> onCreateContractDefinition(CreateContractDefinition cmd) {
        try {
            ContractDefinition contractDefinition = ContractDefinition.Builder.newInstance()
                    .id(cmd.contractDefinitionId)
                    .accessPolicyId(cmd.policyId)
                    .contractPolicyId(cmd.policyId)
                    .assetsSelector(List.of())
                    .build();

            ServiceResult<ContractDefinition> result = contractDefinitionService.create(contractDefinition);

            if (result.succeeded() || isAlreadyExists(result.getFailureDetail())) {
                cmd.replyTo.tell(new OperationResult(true, cmd.contractDefinitionId, null));
            } else {
                cmd.replyTo.tell(new OperationResult(false, null, result.getFailureDetail()));
            }
        } catch (Exception e) {
            getContext().getLog().warn("CreateContractDefinition failed: {}", e.getMessage());
            cmd.replyTo.tell(new OperationResult(false, null, e.getMessage()));
        }
        return this;
    }

    private boolean isAlreadyExists(String failureDetail) {
        return failureDetail != null && failureDetail.toLowerCase().contains("already exists");
    }

    public static Behavior<ManagementCommand> behavior(
            AssetService assetService,
            PolicyDefinitionService policyDefinitionService,
            ContractDefinitionService contractDefinitionService
    ) {
        return Behaviors.setup(ctx ->
                new ProviderManagementActor(ctx, assetService, policyDefinitionService, contractDefinitionService));
    }
}
