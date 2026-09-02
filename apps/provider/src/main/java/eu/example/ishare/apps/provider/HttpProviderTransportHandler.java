package eu.example.ishare.apps.provider;

import eu.example.ishare.apps.common.EdcHttpClient;
import eu.example.ishare.apps.common.HttpProviderEdcGateway;
import eu.example.ishare.domain.provider.ProviderEdcGateway;

import java.net.URI;

public final class HttpProviderTransportHandler implements ProviderTransportHandler {
    private final ProviderEdcGateway gateway;

    public HttpProviderTransportHandler(URI managementUrl) {
        this.gateway = new HttpProviderEdcGateway(managementUrl, new EdcHttpClient());
    }

    @Override
    public ProviderEdcGateway gateway() {
        return gateway;
    }
}
