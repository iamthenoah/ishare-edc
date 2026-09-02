package eu.example.ishare.apps.wallet;

import eu.example.ishare.domain.vc.WalletService;

public interface WalletTransportHandler {
    void start(WalletService service) throws Exception;
}
