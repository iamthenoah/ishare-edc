package eu.example.ishare.apps.issuer;

import eu.example.ishare.domain.vc.IssuerService;

public interface IssuerTransportHandler {
    void start(IssuerService service) throws Exception;
}
