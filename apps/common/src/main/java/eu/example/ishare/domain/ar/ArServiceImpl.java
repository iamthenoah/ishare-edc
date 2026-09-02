package eu.example.ishare.domain.ar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ArServiceImpl implements ArService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String arEori;
    private final String satelliteUrl;
    private final String satelliteEori;
    private final PrivateKey privateKey;
    private final List<X509Certificate> certChain;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    private final Map<String, JsonNode> policies = new ConcurrentHashMap<>();
    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();

    public ArServiceImpl(String arEori, String satelliteUrl, String satelliteEori,
                          PrivateKey privateKey, List<X509Certificate> certChain) {
        this.arEori = arEori;
        this.satelliteUrl = satelliteUrl;
        this.satelliteEori = satelliteEori;
        this.privateKey = privateKey;
        this.certChain = certChain;
    }

    @Override
    public TokenResult issueToken(String clientId, String clientAssertion) {
        try {
            validateWithSatellite(clientId, clientAssertion);
        } catch (Exception e) {
            return TokenResult.unauthorized(e.getMessage());
        }
        try {
            long now = Instant.now().getEpochSecond();
            long exp = now + 3600;
            String token = buildAccessToken(clientId, now, exp);
            tokens.put(token, new TokenEntry(clientId, exp));
            return TokenResult.success(token);
        } catch (Exception e) {
            return TokenResult.failure(e.getMessage());
        }
    }

    @Override
    public DelegationResult requestDelegation(String accessToken, String delegationRequestJson) {
        TokenLookup lookup = resolveToken(accessToken);
        if (!lookup.valid()) {
            return DelegationResult.unauthorized("invalid or expired access token");
        }
        try {
            JsonNode mask = MAPPER.readTree(delegationRequestJson);
            if (mask.has("delegationRequest")) mask = mask.get("delegationRequest");
            JsonNode evidence = buildEvidence(lookup.eori(), mask);
            return DelegationResult.success(signJwt(evidence));
        } catch (Exception e) {
            return DelegationResult.failure(e.getMessage());
        }
    }

    @Override
    public TokenLookup resolveToken(String accessToken) {
        long now = Instant.now().getEpochSecond();
        tokens.entrySet().removeIf(e -> e.getValue().exp() < now);
        TokenEntry entry = tokens.get(accessToken);
        return entry == null ? TokenLookup.invalid() : TokenLookup.of(entry.eori());
    }

    @Override
    public PolicyResult addPolicy(String createdBy, String policyJson) {
        try {
            JsonNode body = MAPPER.readTree(policyJson);
            String newId = body.has("policyId") ? body.get("policyId").asText() : UUID.randomUUID().toString();
            ObjectNode stored = MAPPER.createObjectNode();
            stored.put("policyId", newId);
            stored.put("createdBy", createdBy);
            stored.put("createdAt", Instant.now().toString());
            stored.setAll((ObjectNode) body);
            policies.put(newId, stored);
            return PolicyResult.success(MAPPER.writeValueAsString(Map.of("policyId", newId)));
        } catch (Exception e) {
            return PolicyResult.failure(e.getMessage());
        }
    }

    @Override
    public PolicyResult getPolicies() {
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            policies.values().forEach(arr::add);
            return PolicyResult.success(MAPPER.writeValueAsString(arr));
        } catch (Exception e) {
            return PolicyResult.failure(e.getMessage());
        }
    }

    @Override
    public PolicyResult getPolicy(String id) {
        JsonNode p = policies.get(id);
        if (p == null) return PolicyResult.notFound("not found");
        try {
            return PolicyResult.success(MAPPER.writeValueAsString(p));
        } catch (Exception e) {
            return PolicyResult.failure(e.getMessage());
        }
    }

    @Override
    public PolicyResult deletePolicy(String id) {
        boolean removed = policies.remove(id) != null;
        return removed ? PolicyResult.success("{\"message\":\"Deleted\"}") : PolicyResult.notFound("not found");
    }

    private void validateWithSatellite(String clientId, String clientAssertion) throws Exception {
        String ourAssertion = buildClientAssertion(arEori, satelliteEori);
        String form = "grant_type=client_credentials&scope=iSHARE&client_id=" + arEori
                + "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer&client_assertion=" + ourAssertion;
        HttpResponse<String> tokenResp = postForm(satelliteUrl + "/token", form);
        if (tokenResp.statusCode() != 200) throw new Exception("Cannot auth with satellite: HTTP " + tokenResp.statusCode());
        String satelliteToken = MAPPER.readTree(tokenResp.body()).get("access_token").asText();
        HttpResponse<String> partiesResp = getWithBearer(satelliteUrl + "/parties?eori=" + clientId, satelliteToken);
        if (partiesResp.statusCode() != 200) throw new Exception("Satellite /parties HTTP " + partiesResp.statusCode() + " for " + clientId);
        JsonNode payload = decodePayload(MAPPER.readTree(partiesResp.body()).get("parties_token").asText());
        JsonNode data = payload.path("parties_info").path("data");
        if (!data.isArray() || data.isEmpty()) throw new Exception("Party " + clientId + " not found");
        String status = data.get(0).path("adherence").path("status").asText("");
        if (!"Active".equalsIgnoreCase(status)) throw new Exception("Party " + clientId + " not Active (status=" + status + ")");
        validateAssertionLocally(clientAssertion, clientId);
    }

    private void validateAssertionLocally(String jwt, String clientId) throws Exception {
        JsonNode p = decodePayload(jwt);
        long now = Instant.now().getEpochSecond();
        long exp = p.path("exp").asLong(0);
        if (exp > 0 && now > exp) throw new Exception("client_assertion expired");
        String iss = p.path("iss").asText(""), sub = p.path("sub").asText("");
        if (!clientId.equals(iss) || !clientId.equals(sub)) throw new Exception("iss/sub must equal client_id");
        JsonNode aud = p.get("aud");
        boolean ok = false;
        if (aud != null) {
            if (aud.isArray()) {
                for (JsonNode a : aud) if (arEori.equals(a.asText())) { ok = true; break; }
            } else {
                ok = arEori.equals(aud.asText());
            }
        }
        if (!ok) throw new Exception("aud must contain " + arEori);
    }

    private String buildAccessToken(String clientId, long iat, long exp) throws Exception {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("iss", arEori); p.put("sub", arEori); p.put("aud", arEori); p.put("client_id", clientId);
        p.put("jti", UUID.randomUUID().toString()); p.put("iat", iat); p.put("nbf", iat); p.put("exp", exp);
        p.putArray("scope").add("iSHARE");
        return signJwt(p);
    }

    private String buildClientAssertion(String iss, String aud) throws Exception {
        long now = Instant.now().getEpochSecond();
        ObjectNode p = MAPPER.createObjectNode();
        p.put("iss", iss); p.put("sub", iss); p.put("aud", aud);
        p.put("jti", UUID.randomUUID().toString()); p.put("iat", now); p.put("exp", now + 30);
        return signJwt(p);
    }

    private JsonNode buildEvidence(String requester, JsonNode mask) {
        long now = Instant.now().getEpochSecond();
        String issuer = mask.path("policyIssuer").asText(arEori);
        String accessSubject = requester;
        JsonNode maskTarget = mask.path("target");
        if (maskTarget.isObject() && !maskTarget.path("accessSubject").asText("").isBlank())
            accessSubject = maskTarget.path("accessSubject").asText();
        final String finalAccessSubject = accessSubject;
        ArrayNode sets = MAPPER.createArrayNode();
        policies.values().stream().filter(p -> {
            boolean im = issuer.equals(p.path("policyIssuer").asText(null));
            boolean tm = finalAccessSubject.equals(p.path("target").asText(null));
            return im && tm;
        }).forEach(sets::add);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("iss", arEori); payload.put("sub", arEori); payload.put("aud", requester);
        payload.put("jti", UUID.randomUUID().toString()); payload.put("iat", now); payload.put("exp", now + 30);
        ObjectNode evidence = payload.putObject("delegationEvidence");
        evidence.put("notBefore", now); evidence.put("notOnOrAfter", now + 3600); evidence.put("policyIssuer", issuer);
        evidence.putObject("target").put("accessSubject", finalAccessSubject);
        evidence.set("policySets", sets);
        return payload;
    }

    private String signJwt(JsonNode payload) throws Exception {
        ObjectNode hdr = MAPPER.createObjectNode();
        hdr.put("alg", "RS256"); hdr.put("typ", "JWT");
        ArrayNode x5c = hdr.putArray("x5c");
        for (X509Certificate c : certChain) x5c.add(Base64.getEncoder().encodeToString(c.getEncoded()));
        String h = b64url(MAPPER.writeValueAsBytes(hdr));
        String p = b64url(MAPPER.writeValueAsBytes(payload));
        String input = h + "." + p;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(input.getBytes(StandardCharsets.UTF_8));
        return input + "." + b64url(sig.sign());
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static JsonNode decodePayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new Exception("Invalid JWT");
        return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private HttpResponse<String> postForm(String url, String formBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(formBody)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String url, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .timeout(java.time.Duration.ofSeconds(10))
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private record TokenEntry(String eori, long exp) {
    }
}
