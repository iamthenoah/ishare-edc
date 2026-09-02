package eu.example.ishare.common.protocol;

import akka.actor.typed.ActorRef;
import akka.actor.typed.receptionist.ServiceKey;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public final class ManagementProtocol {
    private ManagementProtocol() {}

    public static final ServiceKey<ManagementCommand> PROVIDER_KEY =
            ServiceKey.create(ManagementCommand.class, "provider-management-actor");
    public static final ServiceKey<ManagementCommand> CONSUMER_KEY =
            ServiceKey.create(ManagementCommand.class, "consumer-management-actor");

    public interface ManagementCommand extends Serializable {}

    public static final class CreateAsset implements ManagementCommand {
        public final String assetId, name, description, contentUrl;
        public final ActorRef<OperationResult> replyTo;
        @JsonCreator
        public CreateAsset(@JsonProperty("assetId") String assetId, @JsonProperty("name") String name,
                           @JsonProperty("description") String description, @JsonProperty("contentUrl") String contentUrl,
                           @JsonProperty("replyTo") ActorRef<OperationResult> replyTo) {
            this.assetId = assetId; this.name = name; this.description = description;
            this.contentUrl = contentUrl; this.replyTo = replyTo;
        }
    }

    public static final class CreatePolicy implements ManagementCommand {
        public final String policyId;
        public final ActorRef<OperationResult> replyTo;
        @JsonCreator
        public CreatePolicy(@JsonProperty("policyId") String policyId, @JsonProperty("replyTo") ActorRef<OperationResult> replyTo) {
            this.policyId = policyId; this.replyTo = replyTo;
        }
    }

    public static final class CreateContractDefinition implements ManagementCommand {
        public final String contractDefinitionId, policyId;
        public final ActorRef<OperationResult> replyTo;
        @JsonCreator
        public CreateContractDefinition(@JsonProperty("contractDefinitionId") String contractDefinitionId,
                                        @JsonProperty("policyId") String policyId,
                                        @JsonProperty("replyTo") ActorRef<OperationResult> replyTo) {
            this.contractDefinitionId = contractDefinitionId; this.policyId = policyId; this.replyTo = replyTo;
        }
    }

    public static final class OperationResult implements ManagementCommand {
        public final boolean success;
        public final String id, error;
        @JsonCreator
        public OperationResult(@JsonProperty("success") boolean success, @JsonProperty("id") String id,
                               @JsonProperty("error") String error) {
            this.success = success; this.id = id; this.error = error;
        }
    }

    public static final class RequestCatalog implements ManagementCommand {
        public final String counterPartyAddress, counterPartyId;
        public final ActorRef<CatalogResult> replyTo;
        @JsonCreator
        public RequestCatalog(@JsonProperty("counterPartyAddress") String counterPartyAddress,
                              @JsonProperty("counterPartyId") String counterPartyId,
                              @JsonProperty("replyTo") ActorRef<CatalogResult> replyTo) {
            this.counterPartyAddress = counterPartyAddress; this.counterPartyId = counterPartyId; this.replyTo = replyTo;
        }
    }

    public static final class CatalogResult implements ManagementCommand {
        public final String assetId, offerId, error;

        @JsonCreator
        public CatalogResult(
                @JsonProperty("assetId") String assetId,
                @JsonProperty("offerId") String offerId,
                @JsonProperty("error") String error
        ) {
            this.assetId = assetId; this.offerId = offerId; this.error = error;
        }
    }

    public static final class StartNegotiation implements ManagementCommand {
        public final String counterPartyAddress, counterPartyId, offerId, assetId;
        public final ActorRef<OperationResult> replyTo;

        @JsonCreator
        public StartNegotiation(
                @JsonProperty("counterPartyAddress") String counterPartyAddress,
                @JsonProperty("counterPartyId") String counterPartyId,
                @JsonProperty("offerId") String offerId,
                @JsonProperty("assetId") String assetId,
                @JsonProperty("replyTo") ActorRef<OperationResult> replyTo
        ) {
            this.counterPartyAddress = counterPartyAddress; this.counterPartyId = counterPartyId;
            this.offerId = offerId; this.assetId = assetId;
            this.replyTo = replyTo;
        }
    }

    public static final class PollNegotiation implements ManagementCommand {
        public final String negotiationId;
        public final ActorRef<NegotiationStateResult> replyTo;
        @JsonCreator
        public PollNegotiation(@JsonProperty("negotiationId") String negotiationId,
                               @JsonProperty("replyTo") ActorRef<NegotiationStateResult> replyTo) {
            this.negotiationId = negotiationId; this.replyTo = replyTo;
        }
    }

    public static final class NegotiationStateResult implements ManagementCommand {
        public final String state, agreementId, error;
        @JsonCreator
        public NegotiationStateResult(@JsonProperty("state") String state, @JsonProperty("agreementId") String agreementId,
                                      @JsonProperty("error") String error) {
            this.state = state; this.agreementId = agreementId; this.error = error;
        }
    }

    public static final class StartTransfer implements ManagementCommand {
        public final String counterPartyAddress, counterPartyId, contractId, assetId, transferType, dataDestinationType, dataDestinationBaseUrl;
        public final ActorRef<OperationResult> replyTo;
        @JsonCreator
        public StartTransfer(@JsonProperty("counterPartyAddress") String counterPartyAddress,
                             @JsonProperty("counterPartyId") String counterPartyId,
                             @JsonProperty("contractId") String contractId, @JsonProperty("assetId") String assetId,
                             @JsonProperty("transferType") String transferType,
                             @JsonProperty("dataDestinationType") String dataDestinationType,
                             @JsonProperty("dataDestinationBaseUrl") String dataDestinationBaseUrl,
                             @JsonProperty("replyTo") ActorRef<OperationResult> replyTo) {
            this.counterPartyAddress = counterPartyAddress; this.counterPartyId = counterPartyId;
            this.contractId = contractId; this.assetId = assetId; this.transferType = transferType;
            this.dataDestinationType = dataDestinationType; this.dataDestinationBaseUrl = dataDestinationBaseUrl;
            this.replyTo = replyTo;
        }
    }

    public static final class Ping implements ManagementCommand {
        public final ActorRef<Pong> replyTo;
        @JsonCreator
        public Ping(@JsonProperty("replyTo") ActorRef<Pong> replyTo) { this.replyTo = replyTo; }
    }

    public static final class Pong implements ManagementCommand {
        @JsonCreator
        public Pong() {}
    }

    public static final class PollTransfer implements ManagementCommand {
        public final String transferId;
        public final ActorRef<TransferStateResult> replyTo;
        @JsonCreator
        public PollTransfer(@JsonProperty("transferId") String transferId, @JsonProperty("replyTo") ActorRef<TransferStateResult> replyTo) {
            this.transferId = transferId; this.replyTo = replyTo;
        }
    }

    public static final class TransferStateResult implements ManagementCommand {
        public final String state, error;
        @JsonCreator
        public TransferStateResult(@JsonProperty("state") String state, @JsonProperty("error") String error) {
            this.state = state; this.error = error;
        }
    }
}
