package eu.example.ishare.extension.identity;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.iam.VerificationContext;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class VcIdentityService implements IdentityService {
    private final String ownPartyId;
    private final String trustedIssuerId;
    private final RSAPublicKey trustedIssuerKey;
    private final WalletServiceHandler walletHandler;
    private final Monitor monitor;

    public VcIdentityService(String ownPartyId, String trustedIssuerId, X509Certificate trustedIssuerCert,
                             WalletServiceHandler walletHandler, Monitor monitor) {
        this.ownPartyId = ownPartyId;
        this.trustedIssuerId = trustedIssuerId;
        this.trustedIssuerKey = (RSAPublicKey) trustedIssuerCert.getPublicKey();
        this.walletHandler = walletHandler;
        this.monitor = monitor;
    }

    @Override
    public Result<TokenRepresentation> obtainClientCredentials(TokenParameters parameters) {
        String counterPartyId = parameters.getStringClaim("aud");
        if (counterPartyId == null || counterPartyId.isBlank()) {
            return Result.failure("TokenParameters must contain 'aud'");
        }
        try {
            String presentation = walletHandler.requestPresentation(counterPartyId);
            return Result.success(TokenRepresentation.Builder.newInstance().token(presentation).build());
        } catch (Exception e) {
            return Result.failure("Failed to obtain presentation from wallet: " + e.getMessage());
        }
    }

    @Override
    public Result<ClaimToken> verifyJwtToken(TokenRepresentation tokenRepresentation, VerificationContext verificationContext) {
        String rawToken = tokenRepresentation.getToken();
        if (rawToken == null || rawToken.isBlank()) return Result.failure("Missing bearer token");
        try {
            SignedJWT presentation = SignedJWT.parse(rawToken);
            JWTClaimsSet vpClaims = presentation.getJWTClaimsSet();
            String holderId = vpClaims.getIssuer();
            if (holderId == null || holderId.isBlank()) return Result.failure("Presentation missing 'iss' claim");
            Date vpExpiry = vpClaims.getExpirationTime();
            if (vpExpiry != null && vpExpiry.before(new Date())) return Result.failure("Presentation has expired");
            List<String> audience = vpClaims.getAudience();
            if (audience != null && !audience.isEmpty() && !audience.contains(ownPartyId)) {
                return Result.failure("Presentation 'aud' does not include " + ownPartyId);
            }
            if (!presentation.verify(new RSASSAVerifier(holderKey(presentation)))) {
                return Result.failure("Presentation signature invalid");
            }

            String credentialJwt = extractCredential(vpClaims);
            if (credentialJwt == null) return Result.failure("Presentation contains no verifiableCredential");
            SignedJWT credential = SignedJWT.parse(credentialJwt);
            JWTClaimsSet vcClaims = credential.getJWTClaimsSet();
            if (!trustedIssuerId.equals(vcClaims.getIssuer())) {
                return Result.failure("Credential issuer '" + vcClaims.getIssuer() + "' is not the trusted issuer");
            }
            if (!credential.verify(new RSASSAVerifier(trustedIssuerKey))) {
                return Result.failure("Credential signature invalid (not signed by trusted issuer)");
            }
            Date vcExpiry = vcClaims.getExpirationTime();
            if (vcExpiry != null && vcExpiry.before(new Date())) return Result.failure("Credential has expired");

            String subjectId = credentialSubjectId(vcClaims);
            if (!holderId.equals(vcClaims.getSubject()) && !holderId.equals(subjectId)) {
                return Result.failure("Credential subject does not match presentation signer");
            }

            ClaimToken claimToken = ClaimToken.Builder.newInstance()
                    .claim("iss", holderId)
                    .claim("sub", holderId)
                    .claim("client_id", holderId)
                    .claim("credential_issuer", trustedIssuerId)
                    .build();
            return Result.success(claimToken);
        } catch (Exception e) {
            monitor.warning("VC verification failed: " + e.getMessage());
            return Result.failure("VC verification failed: " + e.getMessage());
        }
    }

    private RSAPublicKey holderKey(SignedJWT presentation) throws Exception {
        var chain = presentation.getHeader().getX509CertChain();
        if (chain == null || chain.isEmpty()) throw new Exception("Presentation header has no x5c certificate");
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(
                new ByteArrayInputStream(chain.get(0).decode()));
        return (RSAPublicKey) certificate.getPublicKey();
    }

    private static String extractCredential(JWTClaimsSet vpClaims) {
        Object vp = vpClaims.getClaim("vp");
        if (vp instanceof Map<?, ?> vpMap) {
            Object credentials = vpMap.get("verifiableCredential");
            if (credentials instanceof List<?> list && !list.isEmpty()) {
                return list.get(0).toString();
            }
        }
        return null;
    }

    private static String credentialSubjectId(JWTClaimsSet vcClaims) {
        Object vc = vcClaims.getClaim("vc");
        if (vc instanceof Map<?, ?> vcMap) {
            Object subject = vcMap.get("credentialSubject");
            if (subject instanceof Map<?, ?> subjectMap) {
                Object id = subjectMap.get("id");
                if (id != null) return id.toString();
            }
        }
        return null;
    }
}
