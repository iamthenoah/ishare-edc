package eu.example.ishare.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class PerfCompare {
    public static void main(String[] args) throws Exception {
        Path perfRoot = Path.of(System.getProperty("perf.out-dir", "results/perf"));
        Map<String, List<Row>> byConfig = new LinkedHashMap<>();

        try (Stream<Path> dirs = Files.list(perfRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path summary = dir.resolve("summary.csv");
                if (Files.exists(summary)) {
                    for (Row row : readSummary(summary)) {
                        byConfig.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
                    }
                }
            }
        }
        if (byConfig.isEmpty()) {
            System.err.println("No summary.csv files under " + perfRoot + " - run :perf:perfRun first.");
            return;
        }

        StringBuilder md = new StringBuilder();
        md.append("# SOP vs AOP performance comparison\n\n");
        md.append("Closed-loop Java harness (see `perf/`), repeats averaged; CoV = stddev/mean of\n");
        md.append("p50 across repeats (high CoV means the configuration is unstable and should be\n");
        md.append("re-run or investigated before quoting numbers from it).\n");

        TreeSet<String> modes = new TreeSet<>();
        byConfig.values().forEach(rows -> modes.add(rows.get(0).mode));

        for (String mode : modes) {
            md.append("\n## mode = ").append(mode).append("\n\n");
            md.append("| Scenario | Concurrency | Repeats | Throughput (req/s) | Mean (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Server mean (ms) | Errors | p50 CoV |\n");
            md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            byConfig.values().stream()
                    .filter(rows -> rows.get(0).mode.equals(mode))
                    .sorted((a, b) -> {
                        int scenarioOrder = a.get(0).scenario.compareTo(b.get(0).scenario);
                        return scenarioOrder != 0 ? scenarioOrder
                                : Integer.compare(a.get(0).concurrency, b.get(0).concurrency);
                    })
                    .forEach(rows -> {
                        Row first = rows.get(0);
                        double[] p50s = rows.stream().mapToDouble(r -> r.p50).toArray();
                        md.append(String.format(Locale.ROOT,
                                "| %s | %d | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %d | %.1f%% |%n",
                                first.scenario, first.concurrency, rows.size(),
                                avg(rows, r -> r.throughput), avg(rows, r -> r.mean),
                                avg(rows, r -> r.p50), avg(rows, r -> r.p95), avg(rows, r -> r.p99),
                                avg(rows, r -> r.serverMean),
                                rows.stream().mapToLong(r -> r.errors).sum(),
                                coefficientOfVariation(p50s) * 100));
                    });
        }

        Path out = perfRoot.resolve("comparison.md");
        Files.writeString(out, md.toString());
        System.out.println(md);
        System.out.println("Written to " + out);
    }

    private static double avg(List<Row> rows, java.util.function.ToDoubleFunction<Row> getter) {
        return rows.stream().mapToDouble(getter).average().orElse(0);
    }

    private static double coefficientOfVariation(double[] values) {
        if (values.length < 2) return 0;
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        if (mean == 0) return 0;
        double variance = java.util.Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum() / (values.length - 1);
        return Math.sqrt(variance) / mean;
    }

    private static List<Row> readSummary(Path summary) throws IOException {
        List<Row> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(summary);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] f = line.split(",");

            rows.add(new Row(f[1], f[2], Integer.parseInt(f[3]), Long.parseLong(f[7]),
                    Double.parseDouble(f[8]), Double.parseDouble(f[9]), Double.parseDouble(f[10]),
                    Double.parseDouble(f[12]), Double.parseDouble(f[13]), Double.parseDouble(f[15])));
        }
        return rows;
    }

    private record Row(String scenario, String mode, int concurrency, long errors,
                       double throughput, double mean, double p50, double p95, double p99,
                       double serverMean) {
        String key() {
            return scenario + "|" + mode + "|" + concurrency;
        }
    }
}
