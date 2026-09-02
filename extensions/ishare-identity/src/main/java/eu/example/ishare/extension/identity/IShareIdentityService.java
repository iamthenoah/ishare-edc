package eu.example.ishare.extension.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.*;
import org.eclipse.edc.spi.iam.*;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;

import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class IShareIdentityService implements IdentityService {
    private static final String GRANT_TYPE = "client_credentials";
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String SCOPE = "iSHARE";

    private final String ownPartyId;
    private final String prBaseUrl;
    private final String prEori;
    private final String arEori;
    private final boolean arEnforce;
    private final PrivateKey privateKey;
    private final List<X509Certificate> certChain;
    private final OkHttpClient http;
    private final Monitor monitor;
    private final IShareIdentityServiceHandler arHandler;

    private final ConcurrentHashMap<String, CachedDelegation> delegationCache = new ConcurrentHashMap<>();

    private record CachedDelegation(boolean permitted, long notOnOrAfterEpochSeconds) {
    }

    public IShareIdentityService(String ownPartyId, String prBaseUrl, String prEori, String arEori,
                                 boolean arEnforce, PrivateKey privateKey, List<X509Certificate> certChain,
                                 OkHttpClient http, Monitor monitor, IShareIdentityServiceHandler arHandler) {
        this.ownPartyId = ownPartyId;
        this.prBaseUrl = normalise(prBaseUrl);
        this.prEori = prEori;
        this.arEori = arEori;
        this.arEnforce = arEnforce;
        this.privateKey = privateKey;
        this.certChain = certChain;
        this.http = http;
        this.monitor = monitor;
        this.arHandler = arHandler;
    }

    private static String jsonField(String json, String field) {
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        int start = json.indexOf('"', colon + 1) + 1;
        int end = json.indexOf('"', start);
        return (start > 0 && end > start) ? json.substring(start, end) : null;
    }

    private static String normalise(String url) {
        return url == null ? null : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    @Override
    public Result<TokenRepresentation> obtainClientCredentials(TokenParameters parameters) {
        String counterPartyEori = parameters.getStringClaim("aud");
        if (counterPartyEori == null || counterPartyEori.isBlank()) return Result.failure("TokenParameters must contain 'aud'");
        try {
            long now = Instant.now().getEpochSecond();
            String accessToken = buildAccessToken(counterPartyEori, now, now + 3600, null);
            return Result.success(TokenRepresentation.Builder.newInstance().token(accessToken).build());
        } catch (Exception e) {
            return Result.failure("Failed to build iSHARE token: " + e.getMessage());
        }
    }

    @Override
    public Result<ClaimToken> verifyJwtToken(TokenRepresentation tokenRepresentation, VerificationContext verificationContext) {
        String rawToken = tokenRepresentation.getToken();
        if (rawToken == null || rawToken.isBlank()) return Result.failure("Missing bearer token");
        try {
            SignedJWT jwt = SignedJWT.parse(rawToken);
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String consumerEori = claims.getIssuer();
            Date exp = claims.getExpirationTime();

            if (exp != null && exp.before(new Date())) return Result.failure("Bearer token has expired");
            if (consumerEori == null || consumerEori.isBlank()) return Result.failure("Bearer token missing 'iss' claim");
            List<String> aud = claims.getAudience();
            if (aud != null && !aud.isEmpty() && !aud.contains(ownPartyId)) return Result.failure("Bearer token 'aud' does not include " + ownPartyId);

            verifyPartyActive(consumerEori);

            String delegationToken = extractDelegationToken(tokenRepresentation, claims);

            if (arEnforce && hasArTransportConfigured()) {
                try {
                    verifyDelegationForConsumer(consumerEori);
                } catch (Exception e) {
                    return Result.failure("Delegation validation failed: " + e.getMessage());
                }
            }

            ClaimToken claimToken = ClaimToken.Builder.newInstance()
                    .claim("iss", consumerEori)
                    .claim("sub", claims.getSubject())
                    .claim("client_id", consumerEori)
                    .claim("delegation_token", delegationToken != null ? delegationToken : "")
                    .build();
            return Result.success(claimToken);
        } catch (Exception e) {
            return Result.failure("Token verification failed: " + e.getMessage());
        }
    }

    private boolean hasArTransportConfigured() {
        return arHandler != null;
    }

    private void verifyDelegationForConsumer(String consumerEori) throws Exception {
        long now = Instant.now().getEpochSecond();
        CachedDelegation cached = delegationCache.get(consumerEori);
        if (cached != null && now < cached.notOnOrAfterEpochSeconds()) {
            monitor.info("iSHARE AR: reusing cached delegation decision for " + consumerEori
                    + " (valid until epoch " + cached.notOnOrAfterEpochSeconds() + ")");
            if (!cached.permitted()) throw new Exception("No Permit rule found for consumer " + consumerEori + " (cached)");
            return;
        }

        String arAssertion = buildClientAssertion(arEori);
        String arToken = arHandler.requestArToken(ownPartyId, arAssertion);
        String delegationToken = arHandler.requestDelegation(arToken, ownPartyId, consumerEori);

        SignedJWT delJwt = SignedJWT.parse(delegationToken);
        JWTClaimsSet delClaims = delJwt.getJWTClaimsSet();
        if (!arEori.equals(delClaims.getIssuer())) throw new Exception("Delegation token issuer != AR EORI");
        Date delExp = delClaims.getExpirationTime();
        if (delExp != null && delExp.before(new Date())) throw new Exception("Delegation token expired");
        Object evidence = delClaims.getClaim("delegationEvidence");
        if (evidence == null) throw new Exception("No delegationEvidence in delegation token");

        boolean permitted = hasPermit(evidence);
        long notOnOrAfter = extractNotOnOrAfter(evidence, now);
        delegationCache.put(consumerEori, new CachedDelegation(permitted, notOnOrAfter));
        monitor.info("iSHARE AR: fetched fresh delegation decision for " + consumerEori
                + " (permitted=" + permitted + ", valid until epoch " + notOnOrAfter + ")");

        if (!permitted) throw new Exception("No Permit rule found for consumer " + consumerEori);
    }

    private static long extractNotOnOrAfter(Object evidence, long fallbackNow) {
        try {
            if (evidence instanceof Map<?, ?> m) {
                Object v = m.get("notOnOrAfter");
                if (v instanceof Number n) return n.longValue();
            }
        } catch (Exception ignored) {
        }
        return fallbackNow;
    }

    private String extractDelegationToken(TokenRepresentation tokenRepresentation, JWTClaimsSet claims) {
        try {
            Object dt = claims.getClaim("delegation_token");
            if (dt != null && !dt.toString().isBlank()) return dt.toString();
        } catch (Exception ignored) {}
        if (tokenRepresentation.getAdditional() != null) {
            Object dt = tokenRepresentation.getAdditional().get("delegation_token");
            if (dt != null && !dt.toString().isBlank()) return dt.toString();
        }
        return null;
    }

    private void verifyPartyActive(String eori) throws Exception {
        String assertion = buildClientAssertion(prEori);
        String satelliteToken = exchangeToken(prBaseUrl + "/token", assertion);
        Request req = new Request.Builder().url(prBaseUrl + "/parties?eori=" + eori)
                .header("Authorization", "Bearer " + satelliteToken).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("PR /parties returned HTTP " + resp.code() + " for " + eori);
            String body = resp.body().string();
            String partiesJwt = jsonField(body, "parties_token");
            if (partiesJwt == null) throw new Exception("No parties_token in PR response");
            SignedJWT parsed = SignedJWT.parse(partiesJwt);
            String status = extractPartyStatus(parsed.getJWTClaimsSet().getClaim("parties_info"));
            if (!"Active".equalsIgnoreCase(status)) throw new Exception("Party " + eori + " is not Active in PR (status=" + status + ")");
        }
    }

    private String buildAccessToken(String audience, long iat, long exp, String delegationToken) throws Exception {
        var claimsBuilder = new JWTClaimsSet.Builder().issuer(ownPartyId).subject(ownPartyId).audience(audience)
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(Instant.ofEpochSecond(iat)))
                .expirationTime(Date.from(Instant.ofEpochSecond(exp))).claim("client_id", ownPartyId);
        if (delegationToken != null && !delegationToken.isBlank()) claimsBuilder.claim("delegation_token", delegationToken);
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).x509CertChain(encodedCertChain()).build();
        SignedJWT jwt = new SignedJWT(header, claimsBuilder.build());
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    private String buildClientAssertion(String audience) throws Exception {
        Instant now = Instant.now();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).x509CertChain(encodedCertChain()).build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ownPartyId).subject(ownPartyId).audience(audience)
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(30))).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    private List<Base64> encodedCertChain() {
        return certChain.stream().map(c -> {
            try { return Base64.encode(c.getEncoded()); }
            catch (CertificateEncodingException e) { throw new RuntimeException(e); }
        }).toList();
    }

    private String exchangeToken(String tokenEndpoint, String clientAssertion) throws Exception {
        RequestBody body = new FormBody.Builder().add("grant_type", GRANT_TYPE).add("scope", SCOPE)
                .add("client_id", ownPartyId).add("client_assertion_type", ASSERTION_TYPE)
                .add("client_assertion", clientAssertion).build();
        Request req = new Request.Builder().url(tokenEndpoint).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("Token endpoint " + tokenEndpoint + " returned HTTP " + resp.code());
            String token = jsonField(resp.body().string(), "access_token");
            if (token == null) throw new Exception("No access_token in response from " + tokenEndpoint);
            return token;
        }
    }

    private String extractPartyStatus(Object partiesInfo) {
        try {
            if (partiesInfo instanceof Map<?, ?> m) {
                Object data = m.get("data");
                if (data instanceof List<?> list && !list.isEmpty()) {
                    Object party = list.get(0);
                    if (party instanceof Map<?, ?> pm) {
                        Object adh = pm.get("adherence");
                        if (adh instanceof Map<?, ?> am) {
                            Object status = am.get("status");
                            if (status != null) return status.toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private boolean hasPermit(Object delegationEvidence) {
        try {
            if (delegationEvidence instanceof Map<?, ?> evMap) {
                Object policySets = evMap.get("policySets");
                if (policySets instanceof List<?> sets) {
                    for (Object set : sets) {
                        if (set instanceof Map<?, ?> setMap) {
                            Object innerSets = setMap.get("policySets");
                            if (innerSets instanceof List<?> innerList) {
                                for (Object inner : innerList) if (checkPoliciesForPermit(inner)) return true;
                            }
                            if (checkPoliciesForPermit(setMap)) return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean checkPoliciesForPermit(Object setMap) {
        try {
            if (setMap instanceof Map<?, ?> m) {
                Object policies = m.get("policies");
                if (policies instanceof List<?> pols) {
                    for (Object pol : pols) {
                        if (pol instanceof Map<?, ?> polMap) {
                            Object rules = polMap.get("rules");
                            if (rules instanceof List<?> ruleList) {
                                for (Object rule : ruleList) {
                                    if (rule instanceof Map<?, ?> rm) {
                                        Object effect = rm.get("effect");
                                        if ("Permit".equalsIgnoreCase(effect != null ? effect.toString() : "")) return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
