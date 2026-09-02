package eu.example.ishare.domain.vc;

public interface IssuerGateway {
    String requestCredential(String subjectId, String role) throws Exception;
}
