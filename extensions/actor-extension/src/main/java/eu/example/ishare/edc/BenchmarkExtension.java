package eu.example.ishare.edc;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

@Extension("iSHARE Benchmark Endpoint")
public class BenchmarkExtension implements ServiceExtension {
    @Inject private Monitor monitor;

    @Override
    public String name() { return "iSHARE Benchmark Endpoint"; }

    @Override
    public void initialize(ServiceExtensionContext context) {
        int port = context.getSetting("ishare.bench.port", 0);
        if (port <= 0) return;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
            byte[] body = "{\"pong\":true}".getBytes(StandardCharsets.UTF_8);
            server.createContext("/ping", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            monitor.info("BenchmarkExtension: bench endpoint on :" + port + "/ping");
        } catch (Exception e) {
            throw new RuntimeException("BenchmarkExtension failed to start on port " + port, e);
        }
    }
}
