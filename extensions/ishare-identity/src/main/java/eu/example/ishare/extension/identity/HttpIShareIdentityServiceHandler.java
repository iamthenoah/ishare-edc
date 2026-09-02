package eu.example.ishare.extension.identity;

import okhttp3.*;

final class HttpIShareIdentityServiceHandler implements IShareIdentityServiceHandler {
    private static final String GRANT_TYPE = "client_credentials";
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String SCOPE = "iSHARE";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String arBaseUrl;
    private final OkHttpClient http;

    HttpIShareIdentityServiceHandler(String arBaseUrl, OkHttpClient http) {
        this.arBaseUrl = arBaseUrl.endsWith("/") ? arBaseUrl.substring(0, arBaseUrl.length() - 1) : arBaseUrl;
        this.http = http;
    }

    @Override
    public String requestArToken(String clientId, String clientAssertion) throws Exception {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", GRANT_TYPE).add("scope", SCOPE)
                .add("client_id", clientId).add("client_assertion_type", ASSERTION_TYPE)
                .add("client_assertion", clientAssertion).build();
        Request req = new Request.Builder().url(arBaseUrl + "/token").post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("AR /token returned HTTP " + resp.code());
            String token = jsonField(resp.body().string(), "access_token");
            if (token == null) throw new Exception("No access_token in AR /token response");
            return token;
        }
    }

    @Override
    public String requestDelegation(String arToken, String policyIssuer, String accessSubject) throws Exception {
        String delegationBody = "{\"delegationRequest\":{\"policyIssuer\":\"" + policyIssuer + "\","
                + "\"target\":{\"accessSubject\":\"" + accessSubject + "\"},"
                + "\"policySets\":[{\"policies\":[{\"target\":{\"resource\":{\"type\":\"x-default\","
                + "\"identifiers\":[\"*\"],\"attributes\":[\"*\"]},\"actions\":[\"read\"]},"
                + "\"rules\":[{\"effect\":\"Permit\"}]}]}]}}";
        Request req = new Request.Builder().url(arBaseUrl + "/delegation")
                .header("Authorization", "Bearer " + arToken).post(RequestBody.create(delegationBody, JSON)).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("AR /delegation returned HTTP " + resp.code());
            String delegationToken = jsonField(resp.body().string(), "delegation_token");
            if (delegationToken == null) throw new Exception("No delegation_token in AR response");
            return delegationToken;
        }
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
}
