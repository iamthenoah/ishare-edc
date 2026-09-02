package eu.example.ishare.perf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PerfHarness {
    private static final Pattern DURATION_MS = Pattern.compile("\"durationMs\"\\s*:\\s*(\\d+)");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        String baseUrl = prop("perf.base-url", "http://localhost:7002");
        String scenario = prop("perf.scenario", "unlabelled");
        List<String> modes = Arrays.asList(prop("perf.mode", "ping,catalog,run").split(","));
        List<Integer> concurrencies = Arrays.stream(prop("perf.concurrency", "1,4,16").split(","))
                .map(String::trim).map(Integer::parseInt).toList();
        int durationSeconds = Integer.parseInt(prop("perf.duration-seconds", "30"));
        int warmupSeconds = Integer.parseInt(prop("perf.warmup-seconds", "5"));
        int repeats = Integer.parseInt(prop("perf.repeats", "3"));
        Path outDir = Path.of(prop("perf.out-dir", "results/perf")).resolve("scenario-" + scenario);
        Files.createDirectories(outDir);

        PerfHarness harness = new PerfHarness();
        Path summary = outDir.resolve("summary.csv");
        if (!Files.exists(summary)) {
            Files.writeString(summary, "timestamp,scenario,mode,concurrency,repeat,duration_s,requests,errors,"
                    + "throughput_rps,mean_ms,p50_ms,p90_ms,p95_ms,p99_ms,max_ms,"
                    + "server_mean_ms,server_p95_ms\n");
        }

        System.out.printf("Perf harness -> scenario=%s baseUrl=%s modes=%s concurrency=%s "
                        + "duration=%ds warmup=%ds repeats=%d%n",
                scenario, baseUrl, modes, concurrencies, durationSeconds, warmupSeconds, repeats);

        for (String mode : modes) {
            for (int concurrency : concurrencies) {
                for (int repeat = 1; repeat <= repeats; repeat++) {
                    Result result = harness.runOnce(baseUrl, mode.trim(), concurrency,
                            warmupSeconds, durationSeconds);
                    harness.report(result, outDir, summary, scenario, mode.trim(), concurrency, repeat, durationSeconds);
                }
            }
        }
        System.out.println("Done. Summary: " + summary);
    }

    private Result runOnce(String baseUrl, String mode, int concurrency,
                           int warmupSeconds, int durationSeconds) throws Exception {
        URI uri = URI.create(baseUrl + switch (mode) {
            case "ping" -> "/ping";
            case "catalog" -> "/catalog";
            case "run" -> "/run";
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        });

        System.out.printf("[perf] mode=%s concurrency=%d: warmup %ds + measure %ds ... ",
                mode, concurrency, warmupSeconds, durationSeconds);

        AtomicBoolean measuring = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);
        List<List<Sample>> perWorker = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        CountDownLatch started = new CountDownLatch(concurrency);
        AtomicLong warmupErrors = new AtomicLong();

        for (int workerIndex = 0; workerIndex < concurrency; workerIndex++) {
            List<Sample> samples = new ArrayList<>();
            perWorker.add(samples);
            Thread worker = new Thread(() -> {
                started.countDown();
                while (!stop.get()) {
                    long startedAt = System.nanoTime();
                    long serverMs = -1;
                    boolean ok;
                    try {
                        HttpResponse<String> response = client.send(
                                HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(120))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                        ok = response.statusCode() >= 200 && response.statusCode() < 300;
                        if (ok) {
                            Matcher matcher = DURATION_MS.matcher(response.body());
                            if (matcher.find()) serverMs = Long.parseLong(matcher.group(1));
                        }
                    } catch (IOException e) {
                        ok = false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    double elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0;
                    if (measuring.get()) {
                        samples.add(new Sample(elapsedMs, serverMs, ok));
                    } else if (!ok) {
                        warmupErrors.incrementAndGet();
                    }
                }
            }, "perf-worker-" + workerIndex);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        started.await();
        Thread.sleep(warmupSeconds * 1000L);
        measuring.set(true);
        long measureStart = System.nanoTime();
        Thread.sleep(durationSeconds * 1000L);
        measuring.set(false);
        double measuredSeconds = (System.nanoTime() - measureStart) / 1_000_000_000.0;
        stop.set(true);
        for (Thread worker : workers) {
            worker.join(5000);
        }

        List<Sample> all = new ArrayList<>();
        perWorker.forEach(all::addAll);
        System.out.printf("%d requests, %d errors (warmup errors: %d)%n",
                all.size(), all.stream().filter(s -> !s.ok).count(), warmupErrors.get());
        return new Result(all, measuredSeconds);
    }

    private void report(Result result, Path outDir, Path summary, String scenario, String mode,
                        int concurrency, int repeat, int durationSeconds) throws IOException {
        List<Sample> okSamples = result.samples.stream().filter(s -> s.ok).toList();
        long errors = result.samples.size() - okSamples.size();
        double[] latencies = okSamples.stream().mapToDouble(s -> s.clientMs).sorted().toArray();
        double[] serverLatencies = okSamples.stream().filter(s -> s.serverMs >= 0)
                .mapToDouble(s -> (double) s.serverMs).sorted().toArray();

        String rawName = String.format("%s-c%d-r%d-samples.csv", mode, concurrency, repeat);
        StringBuilder raw = new StringBuilder("client_ms,server_ms,ok\n");
        for (Sample sample : result.samples) {
            raw.append(String.format(Locale.ROOT, "%.3f,%d,%b%n", sample.clientMs, sample.serverMs, sample.ok));
        }
        Files.writeString(outDir.resolve(rawName), raw.toString());

        String row = String.format(Locale.ROOT,
                "%s,%s,%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f%n",
                Instant.now(), scenario, mode, concurrency, repeat, durationSeconds,
                result.samples.size(), errors,
                okSamples.size() / result.measuredSeconds,
                mean(latencies), percentile(latencies, 50), percentile(latencies, 90),
                percentile(latencies, 95), percentile(latencies, 99),
                latencies.length == 0 ? 0 : latencies[latencies.length - 1],
                mean(serverLatencies), percentile(serverLatencies, 95));
        Files.writeString(summary, row, java.nio.file.StandardOpenOption.APPEND);

        System.out.printf(Locale.ROOT,
                "[perf]   -> %.1f req/s | mean %.1fms p50 %.1fms p95 %.1fms p99 %.1fms | errors %d%n",
                okSamples.size() / result.measuredSeconds, mean(latencies),
                percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99), errors);
    }

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    static double mean(double[] values) {
        return values.length == 0 ? 0 : Arrays.stream(values).average().orElse(0);
    }

    private static String prop(String key, String defaultValue) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record Sample(double clientMs, long serverMs, boolean ok) {
    }

    private record Result(List<Sample> samples, double measuredSeconds) {
        Result {
            samples = Collections.unmodifiableList(samples);
        }
    }
}
