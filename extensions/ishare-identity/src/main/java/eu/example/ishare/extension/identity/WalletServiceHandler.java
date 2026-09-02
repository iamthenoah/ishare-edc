package eu.example.ishare.extension.identity;

public interface WalletServiceHandler {
    String requestPresentation(String audience) throws Exception;
}
