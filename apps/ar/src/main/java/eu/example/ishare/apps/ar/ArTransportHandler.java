package eu.example.ishare.apps.ar;

import eu.example.ishare.domain.ar.ArService;

public interface ArTransportHandler {
    void start(ArService service) throws Exception;
}
