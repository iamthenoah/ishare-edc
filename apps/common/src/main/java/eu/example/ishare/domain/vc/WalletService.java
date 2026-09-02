package eu.example.ishare.domain.vc;

public interface WalletService {
    void storeCredential(String credentialJwt);

    boolean hasCredential();

    PresentationResult createPresentation(String audience);

    record PresentationResult(String presentationJwt, String error) {
        public static PresentationResult success(String presentationJwt) {
            return new PresentationResult(presentationJwt, null);
        }

        public static PresentationResult failure(String error) {
            return new PresentationResult(null, error);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
