package eu.example.ishare.common.cluster;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import java.util.Arrays;
import java.util.List;

public final class ClusterBootstrap {
    private ClusterBootstrap() {}

    public static List<String> parseSeedNodes(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static Config buildConfig(String host, int port, List<String> seedNodes) {
        return ConfigFactory.empty()
                .withValue("akka.remote.artery.canonical.hostname", ConfigValueFactory.fromAnyRef(host))
                .withValue("akka.remote.artery.canonical.port", ConfigValueFactory.fromAnyRef(port))
                .withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(seedNodes))
                .withFallback(ConfigFactory.load("akka-reference.conf"));
    }
}
