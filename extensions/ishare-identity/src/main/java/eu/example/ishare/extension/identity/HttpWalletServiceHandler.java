package eu.example.ishare.extension.identity;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class HttpWalletServiceHandler implements WalletServiceHandler {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String walletBaseUrl;
    private final OkHttpClient http;

    HttpWalletServiceHandler(String walletBaseUrl, OkHttpClient http) {
        this.walletBaseUrl = walletBaseUrl.endsWith("/")
                ? walletBaseUrl.substring(0, walletBaseUrl.length() - 1)
                : walletBaseUrl;
        this.http = http;
    }

    @Override
    public String requestPresentation(String audience) throws Exception {
        String body = "{\"audience\":\"" + audience + "\"}";
        Request request = new Request.Builder()
                .url(walletBaseUrl + "/presentations")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("Wallet /presentations returned HTTP " + response.code());
            }
            String presentation = jsonField(response.body().string(), "presentation");
            if (presentation == null) throw new Exception("No presentation in wallet response");
            return presentation;
        }
    }

    private static String jsonField(String json, String field) {
        String search = "\"" + field + "\"";
        int index = json.indexOf(search);
        if (index < 0) return null;
        int colon = json.indexOf(':', index + search.length());
        int start = json.indexOf('"', colon + 1) + 1;
        int end = json.indexOf('"', start);
        return (start > 0 && end > start) ? json.substring(start, end) : null;
    }
}
