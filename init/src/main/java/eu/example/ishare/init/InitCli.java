package eu.example.ishare.init;

public final class InitCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }

        switch (args[0]) {
            case "ar" -> InitAr.run();
            case "provider" -> InitProviderEdc.run();
            case "consumer-flow" -> InitConsumerFlow.run();
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("Usage: init <ar|provider|consumer-flow>");
    }
}
