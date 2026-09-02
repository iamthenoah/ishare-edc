package eu.example.ishare.edc;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.connector.controlplane.contract.spi.types.agreement.ContractAgreement;
import org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractNegotiation;
import org.eclipse.edc.connector.controlplane.contract.spi.types.negotiation.ContractRequest;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractOffer;
import org.eclipse.edc.connector.controlplane.services.spi.catalog.CatalogService;
import org.eclipse.edc.connector.controlplane.services.spi.contractnegotiation.ContractNegotiationService;
import org.eclipse.edc.connector.controlplane.services.spi.transferprocess.TransferProcessService;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferRequest;
import org.eclipse.edc.connector.dataplane.http.spi.HttpDataAddress;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.response.StatusResult;

import java.util.concurrent.TimeUnit;

import static eu.example.ishare.common.protocol.ManagementProtocol.*;

public final class ConsumerManagementActor extends AbstractBehavior<ManagementCommand> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CatalogService catalogService;
    private final ContractNegotiationService contractNegotiationService;
    private final TransferProcessService transferProcessService;

    public static Behavior<ManagementCommand> behavior(
            CatalogService catalogService,
            ContractNegotiationService contractNegotiationService,
            TransferProcessService transferProcessService
    ) {
        return Behaviors.setup(ctx ->
                new ConsumerManagementActor(ctx, catalogService, contractNegotiationService, transferProcessService));
    }

    private ConsumerManagementActor(
            ActorContext<ManagementCommand> ctx,
            CatalogService catalogService,
            ContractNegotiationService contractNegotiationService,
            TransferProcessService transferProcessService
    ) {
        super(ctx);
        this.catalogService = catalogService;
        this.contractNegotiationService = contractNegotiationService;
        this.transferProcessService = transferProcessService;
    }

    @Override
    public Receive<ManagementCommand> createReceive() {
        return newReceiveBuilder()
                .onMessage(Ping.class, this::onPing)
                .onMessage(RequestCatalog.class, this::onRequestCatalog)
                .onMessage(StartNegotiation.class, this::onStartNegotiation)
                .onMessage(PollNegotiation.class, this::onPollNegotiation)
                .onMessage(StartTransfer.class, this::onStartTransfer)
                .onMessage(PollTransfer.class, this::onPollTransfer)
                .build();
    }

    private Behavior<ManagementCommand> onPing(Ping cmd) {
        cmd.replyTo.tell(new Pong());
        return this;
    }

    private Behavior<ManagementCommand> onRequestCatalog(RequestCatalog cmd) {
        try {
            QuerySpec querySpec = QuerySpec.Builder.newInstance().build();
            StatusResult<byte[]> statusResult = catalogService
                    .requestCatalog(cmd.counterPartyId, cmd.counterPartyAddress, "dataspace-protocol-http", querySpec)
                    .get(60, TimeUnit.SECONDS);

            if (!statusResult.succeeded()) {
                cmd.replyTo.tell(new CatalogResult(null, null, statusResult.getFailureDetail()));
                return this;
            }

            JsonNode catalog = MAPPER.readTree(statusResult.getContent());
            JsonNode datasets = catalog.path("dcat:dataset");
            JsonNode dataset = datasets.isArray() ? datasets.path(0) : datasets;
            if (dataset.isMissingNode() || dataset.isNull()) {
                cmd.replyTo.tell(new CatalogResult(null, null, "Catalog response did not contain a dataset"));
                return this;
            }
            JsonNode policy = dataset.path("odrl:hasPolicy");
            if (policy.isArray() && !policy.isEmpty()) policy = policy.get(0);
            String assetId = dataset.path("@id").asText();
            String offerId = policy.path("@id").asText();
            if (assetId.isBlank() || offerId.isBlank()) {
                cmd.replyTo.tell(new CatalogResult(null, null, "Catalog response did not contain a usable offer"));
                return this;
            }
            cmd.replyTo.tell(new CatalogResult(assetId, offerId, null));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            getContext().getLog().warn("RequestCatalog failed: {}", msg);
            cmd.replyTo.tell(new CatalogResult(null, null, msg));
        }
        return this;
    }

    private Behavior<ManagementCommand> onStartNegotiation(StartNegotiation cmd) {
        try {
            if (cmd.offerId == null || cmd.offerId.isBlank()) {
                cmd.replyTo.tell(new OperationResult(false, null, "offerId is null (requestCatalog likely timed out silently)"));
                return this;
            }
            Policy policy = Policy.Builder.newInstance()
                    .assigner(cmd.counterPartyId)
                    .target(cmd.assetId)
                    .build();

            ContractOffer offer = ContractOffer.Builder.newInstance()
                    .id(cmd.offerId)
                    .policy(policy)
                    .assetId(cmd.assetId)
                    .build();

            ContractRequest request = ContractRequest.Builder.newInstance()
                    .contractOffer(offer)
                    .counterPartyAddress(cmd.counterPartyAddress)
                    .protocol("dataspace-protocol-http")
                    .build();

            ContractNegotiation negotiation = contractNegotiationService.initiateNegotiation(request);

            if (negotiation == null) {
                cmd.replyTo.tell(new OperationResult(false, null, "initiateNegotiation returned null"));
            } else {
                cmd.replyTo.tell(new OperationResult(true, negotiation.getId(), null));
            }
        } catch (Exception e) {
            getContext().getLog().warn("StartNegotiation failed", e);
            cmd.replyTo.tell(new OperationResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return this;
    }

    private Behavior<ManagementCommand> onPollNegotiation(PollNegotiation cmd) {
        try {
            String state = contractNegotiationService.getState(cmd.negotiationId);
            if (state == null) {
                cmd.replyTo.tell(new NegotiationStateResult(null, null, "negotiation not found"));
                return this;
            }
            String agreementId = null;
            if ("FINALIZED".equalsIgnoreCase(state)) {
                ContractAgreement agreement = contractNegotiationService.getForNegotiation(cmd.negotiationId);
                agreementId = agreement != null ? agreement.getId() : null;
            }
            cmd.replyTo.tell(new NegotiationStateResult(state, agreementId, null));
        } catch (Exception e) {
            getContext().getLog().warn("PollNegotiation failed: {}", e.getMessage());
            cmd.replyTo.tell(new NegotiationStateResult(null, null, e.getMessage()));
        }
        return this;
    }

    private Behavior<ManagementCommand> onStartTransfer(StartTransfer cmd) {
        try {
            var builder = TransferRequest.Builder.newInstance()
                    .contractId(cmd.contractId)
                    .protocol("dataspace-protocol-http")
                    .counterPartyAddress(cmd.counterPartyAddress)
                    .transferType(cmd.transferType);

            if (cmd.dataDestinationType != null && cmd.dataDestinationBaseUrl != null) {
                HttpDataAddress destination = HttpDataAddress.Builder.newInstance()
                        .baseUrl(cmd.dataDestinationBaseUrl)
                        .build();
                builder.dataDestination(destination);
            }

            TransferRequest request = builder.build();
            var result = transferProcessService.initiateTransfer(request);

            if (result.succeeded()) {
                cmd.replyTo.tell(new OperationResult(true, result.getContent().getId(), null));
            } else {
                cmd.replyTo.tell(new OperationResult(false, null, result.getFailureDetail()));
            }
        } catch (Exception e) {
            getContext().getLog().warn("StartTransfer failed", e);
            cmd.replyTo.tell(new OperationResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return this;
    }

    private Behavior<ManagementCommand> onPollTransfer(PollTransfer cmd) {
        try {
            String state = transferProcessService.getState(cmd.transferId);
            cmd.replyTo.tell(new TransferStateResult(state, state == null ? "transfer not found" : null));
        } catch (Exception e) {
            getContext().getLog().warn("PollTransfer failed: {}", e.getMessage());
            cmd.replyTo.tell(new TransferStateResult(null, e.getMessage()));
        }
        return this;
    }
}
