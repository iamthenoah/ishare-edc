package eu.example.ishare.domain.vc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

public final class JwtSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtSupport() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String signJwt(JsonNode payload, PrivateKey privateKey, List<X509Certificate> certChain) throws Exception {
        ObjectNode header = MAPPER.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        ArrayNode x5c = header.putArray("x5c");
        for (X509Certificate cert : certChain) {
            x5c.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
        }
        String signingInput = b64url(MAPPER.writeValueAsBytes(header)) + "." + b64url(MAPPER.writeValueAsBytes(payload));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64url(signature.sign());
    }

    public static boolean verifySignature(String jwt, PublicKey publicKey) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return false;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    public static JsonNode decodePayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid JWT");
        return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
