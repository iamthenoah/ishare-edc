package eu.example.ishare.common.protocol;

import akka.actor.typed.ActorRef;
import akka.actor.typed.receptionist.ServiceKey;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public final class VcProtocol {
    private VcProtocol() {}

    public static final ServiceKey<IssuerCommand> ISSUER_KEY =
            ServiceKey.create(IssuerCommand.class, "issuer-actor");

    public static ServiceKey<WalletCommand> walletKey(String partyId) {
        return ServiceKey.create(WalletCommand.class, "wallet-" + partyId);
    }

    public interface IssuerCommand extends Serializable {}

    public static final class IssueCredential implements IssuerCommand {
        public final String subjectId, role;
        public final ActorRef<CredentialResult> replyTo;
        @JsonCreator
        public IssueCredential(@JsonProperty("subjectId") String subjectId, @JsonProperty("role") String role,
                               @JsonProperty("replyTo") ActorRef<CredentialResult> replyTo) {
            this.subjectId = subjectId; this.role = role; this.replyTo = replyTo;
        }
    }

    public static final class CredentialResult implements IssuerCommand {
        public final String credentialJwt, error;
        @JsonCreator
        public CredentialResult(@JsonProperty("credentialJwt") String credentialJwt, @JsonProperty("error") String error) {
            this.credentialJwt = credentialJwt; this.error = error;
        }
        public boolean isSuccess() { return error == null; }
    }

    public interface WalletCommand extends Serializable {}

    public static final class CreatePresentation implements WalletCommand {
        public final String audience;
        public final ActorRef<PresentationResult> replyTo;
        @JsonCreator
        public CreatePresentation(@JsonProperty("audience") String audience,
                                  @JsonProperty("replyTo") ActorRef<PresentationResult> replyTo) {
            this.audience = audience; this.replyTo = replyTo;
        }
    }

    public static final class PresentationResult implements WalletCommand {
        public final String presentationJwt, error;
        @JsonCreator
        public PresentationResult(@JsonProperty("presentationJwt") String presentationJwt, @JsonProperty("error") String error) {
            this.presentationJwt = presentationJwt; this.error = error;
        }
        public boolean isSuccess() { return error == null; }
    }
}
