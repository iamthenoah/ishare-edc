package eu.example.ishare.domain.ar;

public interface ArService {
    TokenResult issueToken(String clientId, String clientAssertion);

    DelegationResult requestDelegation(String accessToken, String delegationRequestJson);

    TokenLookup resolveToken(String accessToken);

    PolicyResult addPolicy(String createdBy, String policyJson);

    PolicyResult getPolicies();

    PolicyResult getPolicy(String id);

    PolicyResult deletePolicy(String id);

    record TokenResult(String accessToken, String error, boolean unauthorized) {
        public static TokenResult success(String accessToken) {
            return new TokenResult(accessToken, null, false);
        }

        public static TokenResult unauthorized(String error) {
            return new TokenResult(null, error, true);
        }

        public static TokenResult failure(String error) {
            return new TokenResult(null, error, false);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    record DelegationResult(String delegationToken, String error, boolean unauthorized) {
        public static DelegationResult success(String delegationToken) {
            return new DelegationResult(delegationToken, null, false);
        }

        public static DelegationResult unauthorized(String error) {
            return new DelegationResult(null, error, true);
        }

        public static DelegationResult failure(String error) {
            return new DelegationResult(null, error, false);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    record TokenLookup(boolean valid, String eori) {
        public static TokenLookup invalid() {
            return new TokenLookup(false, null);
        }

        public static TokenLookup of(String eori) {
            return new TokenLookup(true, eori);
        }
    }

    record PolicyResult(boolean success, String jsonPayload, String error, boolean notFound) {
        public static PolicyResult success(String jsonPayload) {
            return new PolicyResult(true, jsonPayload, null, false);
        }

        public static PolicyResult notFound(String error) {
            return new PolicyResult(false, null, error, true);
        }

        public static PolicyResult failure(String error) {
            return new PolicyResult(false, null, error, false);
        }
    }
}
