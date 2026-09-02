package eu.example.ishare.domain.vc;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class WalletServiceImpl implements WalletService {
    private final String partyId;
    private final PrivateKey privateKey;
    private final List<X509Certificate> certChain;
    private final long presentationValiditySeconds;
    private final AtomicReference<String> credential = new AtomicReference<>();

    public WalletServiceImpl(String partyId, PrivateKey privateKey, List<X509Certificate> certChain,
                              long presentationValiditySeconds) {
        this.partyId = partyId;
        this.privateKey = privateKey;
        this.certChain = certChain;
        this.presentationValiditySeconds = presentationValiditySeconds;
    }

    @Override
    public void storeCredential(String credentialJwt) {
        credential.set(credentialJwt);
    }

    @Override
    public boolean hasCredential() {
        return credential.get() != null;
    }

    @Override
    public PresentationResult createPresentation(String audience) {
        String credentialJwt = credential.get();
        if (credentialJwt == null) {
            return PresentationResult.failure("wallet holds no credential (issuer not reachable at startup?)");
        }
        if (audience == null || audience.isBlank()) {
            return PresentationResult.failure("audience is required");
        }
        try {
            long now = Instant.now().getEpochSecond();
            ObjectNode payload = JwtSupport.mapper().createObjectNode();
            payload.put("iss", partyId);
            payload.put("sub", partyId);
            payload.put("aud", audience);
            payload.put("jti", "urn:uuid:" + UUID.randomUUID());
            payload.put("iat", now);
            payload.put("nbf", now);
            payload.put("exp", now + presentationValiditySeconds);

            ObjectNode vp = payload.putObject("vp");
            vp.putArray("@context").add("https://www.w3.org/2018/credentials/v1");
            vp.putArray("type").add("VerifiablePresentation");
            vp.putArray("verifiableCredential").add(credentialJwt);

            return PresentationResult.success(JwtSupport.signJwt(payload, privateKey, certChain));
        } catch (Exception e) {
            return PresentationResult.failure(e.getMessage());
        }
    }
}
