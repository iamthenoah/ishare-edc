package eu.example.ishare.domain.vc;

public interface IssuerService {
    CredentialResult issueCredential(String subjectId, String role);

    record CredentialResult(String credentialJwt, String error) {
        public static CredentialResult success(String credentialJwt) {
            return new CredentialResult(credentialJwt, null);
        }

        public static CredentialResult failure(String error) {
            return new CredentialResult(null, error);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
