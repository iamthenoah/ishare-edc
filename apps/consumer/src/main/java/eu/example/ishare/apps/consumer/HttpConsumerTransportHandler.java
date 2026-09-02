package eu.example.ishare.apps.consumer;

import eu.example.ishare.apps.common.EdcHttpClient;
import eu.example.ishare.apps.common.HttpConsumerEdcGateway;
import eu.example.ishare.domain.consumer.ConsumerEdcGateway;

import java.net.URI;

public final class HttpConsumerTransportHandler implements ConsumerTransportHandler {
    private final ConsumerEdcGateway gateway;

    public HttpConsumerTransportHandler(URI managementUrl, URI benchUrl) {
        this.gateway = new HttpConsumerEdcGateway(managementUrl, benchUrl, new EdcHttpClient());
    }

    @Override
    public ConsumerEdcGateway gateway() {
        return gateway;
    }
}
