package eu.example.ishare.apps.consumer;

import eu.example.ishare.domain.consumer.ConsumerEdcGateway;

public interface ConsumerTransportHandler {
    ConsumerEdcGateway gateway();
}
