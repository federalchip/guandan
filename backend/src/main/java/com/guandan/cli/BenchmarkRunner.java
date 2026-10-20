package com.guandan.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import com.guandan.rules.LegalMoveGenerator;
import com.guandan.rules.MoveClassifier;

import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Re-runs the exact B01–B04 T4 inputs, without caching generator results. */
final class BenchmarkRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final int WARMUP = 10;
    private static final int MEASURE = 100;
    private static final double TARGET_P95_MS = 500;

    private BenchmarkRunner() {
    }

    static Map<String, Object> measure() throws Exception {
        JsonNode input;
        try (InputStream in = BenchmarkRunner.class.getResourceAsStream("/fixtures/benchmark_hands.json")) {
            if (in == null) throw new IllegalStateException("missing benchmark_hands.json");
            input = JSON.readTree(in);
        }
        List<Integer> previousIds = ids(input.get("follow_previous").get("card_ids"));
        Move previous = MoveClassifier.classify(previousIds, PROFILE).stream()
                .filter(move -> move.kind() == MoveKind.PAIR).findFirst().orElseThrow();
        Map<String, Double> baseline = loadBaseline();
        long gcBefore = gcCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean allMeet = true;
        for (JsonNode hand : input.get("hands")) {
            String caseId = hand.get("case_id").asText();
            List<Integer> cards = ids(hand.get("card_ids"));
            for (String scenario : List.of("lead", "follow_pair_10")) {
                Move target = scenario.equals("lead") ? null : previous;
                long firstStart = System.nanoTime();
                int candidates = LegalMoveGenerator.generateLegalMoves(cards, target, PROFILE).size();
                double firstMs = (System.nanoTime() - firstStart) / 1e6;
                for (int i = 1; i < WARMUP; i++) LegalMoveGenerator.generateLegalMoves(cards, target, PROFILE);
                long[] times = new long[MEASURE];
                for (int i = 0; i < MEASURE; i++) {
                    long start = System.nanoTime();
                    int actual = LegalMoveGenerator.generateLegalMoves(cards, target, PROFILE).size();
                    times[i] = System.nanoTime() - start;
                    if (actual != candidates) throw new IllegalStateException(caseId + "/" + scenario + " candidate count drift");
                }
                Arrays.sort(times);
                double p50 = times[49] / 1e6;
                double p95 = times[94] / 1e6;
                double max = times[99] / 1e6;
                boolean meets = p95 <= TARGET_P95_MS;
                allMeet &= meets;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("case_id", caseId);
                row.put("scenario", scenario);
                row.put("card_ids", cards);
                row.put("candidates", candidates);
                row.put("first_call_ms", round(firstMs));
                row.put("p50_ms", round(p50));
                row.put("p95_ms", round(p95));
                row.put("max_ms", round(max));
                row.put("meets_p95_target", meets);
                Double oldP95 = baseline.get(caseId + "/" + scenario);
                if (oldP95 != null) {
                    row.put("t4_baseline_p95_ms", oldP95);
                    row.put("p95_ratio_to_t4", round(p95 / oldP95));
                }
                rows.add(row);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("profile_id", PROFILE.profileId());
        report.put("generated_at", Instant.now().toString());
        report.put("environment", GuandanCli.environment());
        report.put("fixture", "benchmark_hands.json B01-B04; original T4 inputs");
        report.put("warmup_iterations", WARMUP);
        report.put("measure_iterations", MEASURE);
        report.put("result_caching", "none");
        report.put("gc_count_during_benchmark", gcCount() - gcBefore);
        report.put("target_p95_ms", TARGET_P95_MS);
        report.put("scenarios", rows);
        report.put("all_meet_p95_target", allMeet);
        return report;
    }

    private static Map<String, Double> loadBaseline() throws Exception {
        Path path = Path.of("artifacts", "t4-generation-baseline.json");
        if (!Files.exists(path)) return Map.of();
        JsonNode root = JSON.readTree(path.toFile());
        Map<String, Double> result = new LinkedHashMap<>();
        for (JsonNode row : root.path("scenarios")) {
            result.put(row.path("case_id").asText() + "/" + row.path("scenario").asText(),
                    row.path("p95_ms").asDouble());
        }
        return result;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(count -> count >= 0).sum();
    }

    private static List<Integer> ids(JsonNode nodes) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode node : nodes) out.add(node.asInt());
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
