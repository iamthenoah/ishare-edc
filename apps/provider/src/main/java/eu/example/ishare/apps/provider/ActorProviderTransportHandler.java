package eu.example.ishare.apps.provider;

import eu.example.ishare.apps.common.ActorProviderEdcGateway;
import eu.example.ishare.domain.provider.ProviderEdcGateway;

import java.time.Duration;
import java.util.List;

public final class ActorProviderTransportHandler implements ProviderTransportHandler {
    private final ProviderEdcGateway gateway;

    public ActorProviderTransportHandler(String host, int port, List<String> seedNodes,
                                          Duration askTimeout, Duration discoveryTimeout) {
        this.gateway = ActorProviderEdcGateway.start(host, port, seedNodes, askTimeout, discoveryTimeout);
    }

    @Override
    public ProviderEdcGateway gateway() {
        return gateway;
    }
}
