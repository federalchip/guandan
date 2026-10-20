package com.guandan.cli;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.domain.RuleProfile;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline diagnostic commands. No Spring context or HTTP listener is created. */
public final class GuandanCli {
    private static final ObjectMapper JSON = new ObjectMapper();

    private GuandanCli() {
    }

    public static boolean isCommand(String arg) {
        return "simulate".equals(arg) || "replay".equals(arg) || "benchmark".equals(arg);
    }

    public static int run(String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException("missing command");
            Map<String, String> options = parseOptions(args);
            return switch (args[0]) {
                case "simulate" -> simulate(options);
                case "replay" -> replay(options);
                case "benchmark" -> benchmark(options);
                default -> throw new IllegalArgumentException("unknown command: " + args[0]);
            };
        } catch (IllegalArgumentException error) {
            System.err.println("[cli] " + error.getMessage());
            return 2;
        } catch (Exception error) {
            System.err.println("[cli] " + error.getMessage());
            return 1;
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        if ((args.length - 1) % 2 != 0) throw new IllegalArgumentException("options require values");
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("invalid or duplicate option: " + args[i]);
            }
        }
        return options;
    }

    private static long longOption(Map<String, String> options, String name, long fallback) {
        try {
            return Long.parseLong(options.getOrDefault(name, Long.toString(fallback)));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        long value = longOption(options, name, fallback);
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException(name + " must be positive");
        return (int) value;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return Path.of(value);
    }

    private static void allowOnly(Map<String, String> options, String... names) {
        List<String> allowed = Arrays.asList(names);
        for (String name : options.keySet()) {
            if (!allowed.contains(name)) throw new IllegalArgumentException("unknown option: " + name);
        }
    }

    private static int simulate(Map<String, String> options) throws IOException {
        allowOnly(options, "--start-seed", "--games", "--max-actions", "--output");
        long startSeed = longOption(options, "--start-seed", 0);
        int count = intOption(options, "--games", 1);
        int guard = intOption(options, "--max-actions", 512);
        if (startSeed > Long.MAX_VALUE - count + 1) throw new IllegalArgumentException("seed range overflows long");
        Path output = requiredPath(options, "--output");
        createParent(output);
        List<Double> computeTimes = new ArrayList<>(count);
        long wallStart = System.nanoTime();
        int completed = 0;
        try (JsonGenerator writer = JSON.getFactory().createGenerator(Files.newOutputStream(output))) {
            writer.useDefaultPrettyPrinter();
            writer.writeStartObject();
            writer.writeStringField("profile_id", RuleProfile.V1_PROFILE_ID);
            writer.writeStringField("policy_seed_derivation", SimulationRunner.POLICY_SEED_VERSION);
            writer.writeStringField("generated_at", Instant.now().toString());
            writer.writeNumberField("start_seed", startSeed);
            writer.writeNumberField("requested_games", count);
            writer.writeNumberField("max_actions_guard", guard);
            writer.writeObjectField("environment", environment());
            writer.writeArrayFieldStart("games");
            for (int i = 0; i < count; i++) {
                long seed = startSeed + i;
                try {
                    SimulationRunner.GameTrace game = SimulationRunner.run(seed, guard);
                    JSON.writeValue(writer, game);
                    computeTimes.add(game.computeMs());
                    completed++;
                } catch (SimulationRunner.SimulationFailure failure) {
                    Path fixture = output.resolveSibling(output.getFileName() + ".failure-seed-" + seed + ".json");
                    JSON.writerWithDefaultPrettyPrinter().writeValue(fixture.toFile(), Map.of(
                            "failure", Map.of("seed", seed, "step", failure.step(),
                                    "error", failure.getMessage(), "max_actions_guard", guard),
                            "initial_hands", failure.initialHands(),
                            "completed_steps", failure.completedSteps()));
                    System.err.println("[simulate] reproducible failure saved: " + fixture.toAbsolutePath());
                    throw failure;
                }
            }
            writer.writeEndArray();
            writer.writeNumberField("games_run", completed);
            writer.writeNumberField("total_wall_ms", (System.nanoTime() - wallStart) / 1e6);
            writer.writeObjectField("compute_ms_stats", statistics(computeTimes));
            writer.writeEndObject();
        }
        System.out.println("[simulate] " + completed + " games passed: " + output.toAbsolutePath());
        return 0;
    }

    private static int replay(Map<String, String> options) throws IOException {
        allowOnly(options, "--input");
        Path input = requiredPath(options, "--input");
        JsonNode root = JSON.readTree(input.toFile());
        if (root.has("failure")) {
            JsonNode f = root.get("failure");
            if (!f.path("seed").isIntegralNumber() || !f.path("step").isIntegralNumber()
                    || !f.path("max_actions_guard").isIntegralNumber()
                    || !f.path("error").isTextual()) {
                throw new IllegalArgumentException("incomplete failure record");
            }
            long seed = f.path("seed").asLong();
            int guard = f.path("max_actions_guard").asInt();
            int expectedStep = f.path("step").asInt();
            try {
                SimulationRunner.run(seed, guard);
                throw new IllegalStateException("seed=" + seed + " failure did not reproduce");
            } catch (SimulationRunner.SimulationFailure reproduced) {
                if (reproduced.step() != expectedStep) throw new IllegalStateException(
                        "seed=" + seed + " failure moved to step=" + reproduced.step());
                if (!JSON.readTree(JSON.writeValueAsBytes(reproduced.initialHands()))
                        .equals(root.path("initial_hands"))) {
                    throw new IllegalStateException("seed=" + seed + " step=" + expectedStep
                            + ": initial hands differ");
                }
                if (!JSON.readTree(JSON.writeValueAsBytes(reproduced.completedSteps()))
                        .equals(root.path("completed_steps"))) {
                    throw new IllegalStateException("seed=" + seed + " step=" + expectedStep
                            + ": completed steps differ");
                }
                if (!reproduced.getMessage().equals(f.path("error").asText())) {
                    throw new IllegalStateException("seed=" + seed + " step=" + expectedStep
                            + ": failure reason differs");
                }
                System.out.println("[replay] reproduced " + reproduced.getMessage());
                return 0;
            }
        }
        JsonNode games = root.has("games") ? root.get("games") : null;
        if (games != null && games.isArray()) {
            int count = 0;
            for (JsonNode node : games) {
                SimulationRunner.replay(JSON.treeToValue(node, SimulationRunner.GameTrace.class));
                count++;
            }
            if (count != root.path("games_run").asInt(-1)) throw new IllegalStateException("record count differs");
            System.out.println("[replay] verified " + count + " games");
        } else {
            SimulationRunner.replay(JSON.treeToValue(root, SimulationRunner.GameTrace.class));
            System.out.println("[replay] verified seed=" + root.path("seed").asLong());
        }
        return 0;
    }

    private static int benchmark(Map<String, String> options) throws Exception {
        allowOnly(options, "--output");
        Path output = requiredPath(options, "--output");
        createParent(output);
        Map<String, Object> report = BenchmarkRunner.measure();
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("[benchmark] " + report.get("all_meet_p95_target") + ": " + output.toAbsolutePath());
        return 0;
    }

    static Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("cpu_model", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        env.put("jdk", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        env.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments());
        env.put("processors", Runtime.getRuntime().availableProcessors());
        env.put("heap_max_mb", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        env.put("gc_collectors", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList());
        return env;
    }

    private static Map<String, Double> statistics(List<Double> times) {
        double[] sorted = times.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return Map.of("p50_ms", percentile(sorted, 0.50), "p95_ms", percentile(sorted, 0.95),
                "max_ms", sorted[sorted.length - 1]);
    }

    private static double percentile(double[] sorted, double q) {
        return sorted[Math.max(0, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}
