package eu.example.ishare.apps.consumer;

import eu.example.ishare.apps.common.ActorConsumerEdcGateway;
import eu.example.ishare.domain.consumer.ConsumerEdcGateway;

import java.time.Duration;
import java.util.List;

public final class ActorConsumerTransportHandler implements ConsumerTransportHandler {
    private final ConsumerEdcGateway gateway;

    public ActorConsumerTransportHandler(String host, int port, List<String> seedNodes, Duration askTimeout, Duration discoveryTimeout) {
        this.gateway = ActorConsumerEdcGateway.start(host, port, seedNodes, askTimeout, discoveryTimeout);
    }

    @Override
    public ConsumerEdcGateway gateway() {
        return gateway;
    }
}
