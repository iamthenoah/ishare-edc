package eu.example.ishare.extension.identity;

public interface IShareIdentityServiceHandler {
    String requestArToken(String clientId, String clientAssertion) throws Exception;

    String requestDelegation(String arToken, String policyIssuer, String accessSubject) throws Exception;
}
