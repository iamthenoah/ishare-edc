package eu.example.ishare.domain.consumer;

public final class ConsumerFlowException extends Exception {
    private final String negotiationId;
    private final String transferId;

    public ConsumerFlowException(String message, String negotiationId, String transferId) {
        super(message);
        this.negotiationId = negotiationId;
        this.transferId = transferId;
    }

    public String negotiationId() {
        return negotiationId;
    }

    public String transferId() {
        return transferId;
    }
}
