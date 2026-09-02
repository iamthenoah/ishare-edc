package eu.example.ishare.domain.vc;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IssuerServiceImpl implements IssuerService {
    private final String issuerId;
    private final PrivateKey privateKey;
    private final List<X509Certificate> certChain;
    private final long validitySeconds;

    public IssuerServiceImpl(String issuerId, PrivateKey privateKey, List<X509Certificate> certChain, long validitySeconds) {
        this.issuerId = issuerId;
        this.privateKey = privateKey;
        this.certChain = certChain;
        this.validitySeconds = validitySeconds;
    }

    @Override
    public CredentialResult issueCredential(String subjectId, String role) {
        if (subjectId == null || subjectId.isBlank()) {
            return CredentialResult.failure("subjectId is required");
        }
        try {
            long now = Instant.now().getEpochSecond();
            ObjectNode payload = JwtSupport.mapper().createObjectNode();
            payload.put("iss", issuerId);
            payload.put("sub", subjectId);
            payload.put("jti", "urn:uuid:" + UUID.randomUUID());
            payload.put("iat", now);
            payload.put("nbf", now);
            payload.put("exp", now + validitySeconds);

            ObjectNode vc = payload.putObject("vc");
            vc.putArray("@context").add("https://www.w3.org/2018/credentials/v1");
            vc.putArray("type").add("VerifiableCredential").add("DataspaceParticipantCredential");
            ObjectNode subject = vc.putObject("credentialSubject");
            subject.put("id", subjectId);
            subject.put("role", role == null || role.isBlank() ? "Participant" : role);
            subject.put("status", "Active");

            return CredentialResult.success(JwtSupport.signJwt(payload, privateKey, certChain));
        } catch (Exception e) {
            return CredentialResult.failure(e.getMessage());
        }
    }
}
