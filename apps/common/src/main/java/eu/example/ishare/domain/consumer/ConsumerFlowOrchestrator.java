package eu.example.ishare.domain.consumer;

public final class ConsumerFlowOrchestrator {
    private static final long POLL_INTERVAL_MS = 5;

    private ConsumerFlowOrchestrator() {
    }

    public static FlowResult runFlow(ConsumerEdcGateway gateway, FlowRequest request) throws ConsumerFlowException {
        long startedAt = System.nanoTime();
        try {
            CatalogOffer offer = gateway.requestCatalog(request.counterPartyAddress(), request.counterPartyId());

            String negotiationId = gateway.startNegotiation(
                    request.counterPartyAddress(), request.counterPartyId(), offer.offerId(), offer.assetId());

            long negotiationDeadline = System.nanoTime() + request.pollTimeoutSeconds() * 1_000_000_000L;
            String agreementId = null;
            String negotiationState = null;
            while (System.nanoTime() < negotiationDeadline) {
                NegotiationState state = gateway.pollNegotiation(negotiationId);
                if ("FINALIZED".equalsIgnoreCase(state.state())) {
                    agreementId = state.agreementId();
                    negotiationState = state.state();
                    break;
                }
                if ("TERMINATED".equalsIgnoreCase(state.state()) || "ERROR".equalsIgnoreCase(state.state())) {
                    throw new ConsumerFlowException("negotiation ended in state " + state.state(), negotiationId, null);
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            if (agreementId == null) {
                throw new ConsumerFlowException("contract negotiation did not finalize within the polling window", negotiationId, null);
            }

            String transferId = gateway.startTransfer(
                    request.counterPartyAddress(), request.counterPartyId(), agreementId, offer.assetId(),
                    request.transferType(), request.dataDestinationType(), request.dataDestinationBaseUrl());

            long transferDeadline = System.nanoTime() + request.pollTimeoutSeconds() * 1_000_000_000L;
            String transferState = null;
            while (System.nanoTime() < transferDeadline) {
                TransferState state = gateway.pollTransfer(transferId);
                if ("STARTED".equalsIgnoreCase(state.state()) || "COMPLETED".equalsIgnoreCase(state.state())) {
                    transferState = state.state();
                    break;
                }
                if ("TERMINATED".equalsIgnoreCase(state.state()) || "ERROR".equalsIgnoreCase(state.state())) {
                    throw new ConsumerFlowException("transfer ended in state " + state.state(), negotiationId, transferId);
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            if (transferState == null) {
                throw new ConsumerFlowException("transfer did not start within the polling window", negotiationId, transferId);
            }

            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            return new FlowResult(offer, negotiationId, agreementId, negotiationState, transferId, transferState, durationMs);
        } catch (ConsumerFlowException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsumerFlowException(e.getMessage(), null, null);
        } catch (Exception e) {
            throw new ConsumerFlowException(e.getClass().getSimpleName() + ": " + e.getMessage(), null, null);
        }
    }

    public record FlowRequest(String counterPartyAddress, String counterPartyId, String transferType,
                               String dataDestinationType, String dataDestinationBaseUrl, int pollTimeoutSeconds) {
    }

    public record FlowResult(CatalogOffer offer, String negotiationId, String agreementId, String negotiationState,
                              String transferId, String transferState, long durationMs) {
    }
}
