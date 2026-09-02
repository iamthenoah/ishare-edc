package eu.example.ishare.common.protocol;

import akka.actor.typed.ActorRef;
import akka.actor.typed.receptionist.ServiceKey;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public final class ArProtocol {
    private ArProtocol() {}

    public static final ServiceKey<ArCommand> SERVICE_KEY =
            ServiceKey.create(ArCommand.class, "ar-actor-service");

    public interface ArCommand extends Serializable {}

    public static final class IssueToken implements ArCommand {
        public final String clientId, clientAssertion;
        public final ActorRef<TokenResponse> replyTo;
        @JsonCreator
        public IssueToken(@JsonProperty("clientId") String clientId,
                          @JsonProperty("clientAssertion") String clientAssertion,
                          @JsonProperty("replyTo") ActorRef<TokenResponse> replyTo) {
            this.clientId = clientId; this.clientAssertion = clientAssertion; this.replyTo = replyTo;
        }
    }

    public static final class TokenResponse implements ArCommand {
        public final String accessToken, error;
        @JsonCreator
        public TokenResponse(@JsonProperty("accessToken") String accessToken, @JsonProperty("error") String error) {
            this.accessToken = accessToken; this.error = error;
        }
        public boolean isSuccess() { return error == null; }
    }

    public static final class RequestDelegation implements ArCommand {
        public final String accessToken, delegationRequestJson;
        public final ActorRef<DelegationResponse> replyTo;
        @JsonCreator
        public RequestDelegation(@JsonProperty("accessToken") String accessToken,
                                 @JsonProperty("delegationRequestJson") String delegationRequestJson,
                                 @JsonProperty("replyTo") ActorRef<DelegationResponse> replyTo) {
            this.accessToken = accessToken; this.delegationRequestJson = delegationRequestJson; this.replyTo = replyTo;
        }
    }

    public static final class DelegationResponse implements ArCommand {
        public final String delegationToken, error;
        @JsonCreator
        public DelegationResponse(@JsonProperty("delegationToken") String delegationToken, @JsonProperty("error") String error) {
            this.delegationToken = delegationToken; this.error = error;
        }
        public boolean isSuccess() { return error == null; }
    }

    public static final class AddPolicy implements ArCommand {
        public final String policyJson, createdBy;
        public final ActorRef<PolicyResult> replyTo;
        @JsonCreator
        public AddPolicy(@JsonProperty("policyJson") String policyJson, @JsonProperty("createdBy") String createdBy,
                         @JsonProperty("replyTo") ActorRef<PolicyResult> replyTo) {
            this.policyJson = policyJson; this.createdBy = createdBy; this.replyTo = replyTo;
        }
    }

    public static final class GetPolicies implements ArCommand {
        public final ActorRef<PolicyResult> replyTo;
        @JsonCreator
        public GetPolicies(@JsonProperty("replyTo") ActorRef<PolicyResult> replyTo) { this.replyTo = replyTo; }
    }

    public static final class GetPolicy implements ArCommand {
        public final String id;
        public final ActorRef<PolicyResult> replyTo;
        @JsonCreator
        public GetPolicy(@JsonProperty("id") String id, @JsonProperty("replyTo") ActorRef<PolicyResult> replyTo) {
            this.id = id; this.replyTo = replyTo;
        }
    }

    public static final class DeletePolicy implements ArCommand {
        public final String id;
        public final ActorRef<PolicyResult> replyTo;
        @JsonCreator
        public DeletePolicy(@JsonProperty("id") String id, @JsonProperty("replyTo") ActorRef<PolicyResult> replyTo) {
            this.id = id; this.replyTo = replyTo;
        }
    }

    public static final class PolicyResult implements ArCommand {
        public final boolean success;
        public final String jsonPayload, error;
        @JsonCreator
        public PolicyResult(@JsonProperty("success") boolean success, @JsonProperty("jsonPayload") String jsonPayload,
                            @JsonProperty("error") String error) {
            this.success = success; this.jsonPayload = jsonPayload; this.error = error;
        }
    }
}
