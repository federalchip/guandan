package com.guandan.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.domain.CardIds;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4c：固定复杂手牌基准。{@code test_benchmark_hands_are_valid} 为普通校验（随常规回归运行），
 * {@code benchmarkGeneration} 打 performance 标签，仅在 -Pperformance 下运行，输出真实基线到
 * artifacts/t4-generation-baseline.json。每次测量都调用真实生产生成器，不缓存结果。
 */
class GenerationBenchmarkTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final int WARMUP = 10;
    private static final int MEASURE = 100;
    private static final double TARGET_P95_MS = 500.0;

    private static final JsonNode ROOT = load();

    private static JsonNode load() {
        try (InputStream in = GenerationBenchmarkTest.class.getResourceAsStream("/fixtures/benchmark_hands.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: /fixtures/benchmark_hands.json");
            }
            return new ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static List<Integer> idsOf(JsonNode node, String field) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode n : node.get(field)) {
            ids.add(n.asInt());
        }
        return ids;
    }

    private static Move previousPair() {
        List<Integer> table = idsOf(ROOT.get("follow_previous"), "card_ids");
        return MoveClassifier.classify(table, PROFILE).stream()
                .filter(m -> m.kind() == MoveKind.PAIR)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("跟牌桌面应为对子"));
    }

    @Test
    void test_benchmark_hands_are_valid() {
        List<Integer> table = idsOf(ROOT.get("follow_previous"), "card_ids");
        assertEquals(2, table.size());
        assertEquals(new HashSet<>(table).size(), table.size(), "桌面牌不重复");

        int handCount = 0;
        for (JsonNode hand : ROOT.get("hands")) {
            handCount++;
            String caseId = hand.get("case_id").asText();
            List<Integer> ids = idsOf(hand, "card_ids");
            List<String> symbols = new ArrayList<>();
            for (JsonNode s : hand.get("card_symbols")) {
                symbols.add(s.asText());
            }
            assertEquals(27, ids.size(), caseId + " 恰为 27 张");
            assertEquals(27, symbols.size(), caseId + " 符号数一致");
            Set<Integer> unique = new HashSet<>(ids);
            assertEquals(27, unique.size(), caseId + " 27 个不同 CardId");
            for (int id : ids) {
                assertTrue(id >= 0 && id <= 107, caseId + " CardId 合法: " + id);
            }
            // 符号与 id 一致
            assertEquals(new HashSet<>(CardIds.parse(String.join(" ", symbols))), unique,
                    caseId + " 符号展开与 card_ids 一致");
            // 桌面牌与手牌不重叠
            for (int t : table) {
                assertFalse(unique.contains(t), caseId + " 桌面牌与手牌不重叠");
            }
            // 领出与跟牌均可生成
            List<Move> lead = LegalMoveGenerator.generateLegalMoves(ids, null, PROFILE);
            assertFalse(lead.isEmpty(), caseId + " 领出有候选");
            List<Move> follow = LegalMoveGenerator.generateLegalMoves(ids, previousPair(), PROFILE);
            assertFalse(follow.isEmpty(), caseId + " 跟对子有候选");
        }
        assertEquals(4, handCount, "B01-B04 四例");
    }

    @Test
    @Tag("performance")
    void benchmarkGeneration() throws Exception {
        Move previous = previousPair();
        List<String> gcNames = new ArrayList<>();
        long gcCountBefore = 0;
        long gcTimeBefore = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcNames.add(gc.getName());
            gcCountBefore += Math.max(0, gc.getCollectionCount());
            gcTimeBefore += Math.max(0, gc.getCollectionTime());
        }

        List<Map<String, Object>> scenarios = new ArrayList<>();
        double maxP95 = 0;
        boolean allMeet = true;

        for (JsonNode hand : ROOT.get("hands")) {
            String caseId = hand.get("case_id").asText();
            List<Integer> ids = idsOf(hand, "card_ids");
            for (String scenario : new String[] {"lead", "follow_pair_10"}) {
                Move prev = scenario.equals("lead") ? null : previous;
                // 该场景预热前的首次调用；同一测试进程可能已运行其他场景或校验测试，不代表 JVM 冷启动。
                // 单独记录，不并入稳态分位数。
                long firstStart = System.nanoTime();
                int candidates = LegalMoveGenerator.generateLegalMoves(ids, prev, PROFILE).size();
                double firstCallMs = (System.nanoTime() - firstStart) / 1e6;
                // 其余预热
                for (int i = 1; i < WARMUP; i++) {
                    LegalMoveGenerator.generateLegalMoves(ids, prev, PROFILE);
                }
                // 测量（每次重新计算，不缓存）
                long[] nanos = new long[MEASURE];
                for (int i = 0; i < MEASURE; i++) {
                    long t0 = System.nanoTime();
                    int c = LegalMoveGenerator.generateLegalMoves(ids, prev, PROFILE).size();
                    nanos[i] = System.nanoTime() - t0;
                    assertEquals(candidates, c, caseId + "/" + scenario + " 候选数应稳定");
                }
                Arrays.sort(nanos);
                double p50 = nanos[(int) Math.ceil(0.50 * MEASURE) - 1] / 1e6;
                double p95 = nanos[(int) Math.ceil(0.95 * MEASURE) - 1] / 1e6;
                double max = nanos[MEASURE - 1] / 1e6;
                boolean meets = p95 <= TARGET_P95_MS;
                allMeet &= meets;
                maxP95 = Math.max(maxP95, p95);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("case_id", caseId);
                row.put("scenario", scenario);
                row.put("candidates", candidates);
                row.put("first_call_ms", round3(firstCallMs));
                row.put("p50_ms", round3(p50));
                row.put("p95_ms", round3(p95));
                row.put("max_ms", round3(max));
                row.put("meets_p95_target", meets);
                scenarios.add(row);
                System.out.printf("[bench] %s/%s candidates=%d first=%.3fms p50=%.3fms p95=%.3fms max=%.3fms meets=%b%n",
                        caseId, scenario, candidates, firstCallMs, p50, p95, max, meets);
            }
        }

        long gcCountAfter = 0;
        long gcTimeAfter = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCountAfter += Math.max(0, gc.getCollectionCount());
            gcTimeAfter += Math.max(0, gc.getCollectionTime());
        }

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("cpu_model", cpuModel());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        env.put("jdk", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        env.put("vm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        env.put("processors", Runtime.getRuntime().availableProcessors());
        env.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments());
        env.put("heap_max_mb", mem.getHeapMemoryUsage().getMax() / (1024 * 1024));
        env.put("gc_collectors", gcNames);
        env.put("gc_count_during_benchmark", gcCountAfter - gcCountBefore);
        env.put("gc_time_ms_during_benchmark", gcTimeAfter - gcTimeBefore);
        env.put("result_caching", "none: 每次调用新建生成器并重新计算，无跨调用缓存");
        env.put("warmup_iterations", WARMUP);
        env.put("measure_iterations", MEASURE);

        List<Map<String, Object>> inputs = new ArrayList<>();
        for (JsonNode hand : ROOT.get("hands")) {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("case_id", hand.get("case_id").asText());
            in.put("card_ids", idsOf(hand, "card_ids"));
            inputs.add(in);
        }
        Map<String, Object> tableIn = new LinkedHashMap<>();
        tableIn.put("case_id", "follow_previous");
        tableIn.put("card_ids", idsOf(ROOT.get("follow_previous"), "card_ids"));
        inputs.add(tableIn);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("profile_id", PROFILE.profileId());
        report.put("generated_at", Instant.now().toString());
        report.put("target_p95_ms", TARGET_P95_MS);
        report.put("environment", env);
        report.put("inputs", inputs);
        report.put("scenarios", scenarios);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("all_meet_p95_target", allMeet);
        summary.put("max_p95_ms", round3(maxP95));
        summary.put("note", "T4c 建立基线；first_call_ms 为各场景预热前的一次调用耗时，并非 JVM 冷启动；p50/p95/max 为预热后稳态；"
                + "无结果缓存，每次调用重新计算，不以缓存命中替代首次计算性能。达标与否如实记录，"
                + "未达标差距进入 T10 完整性能验收，不通过减少合法候选提速。");
        report.put("summary", summary);

        Path dir = Path.of("artifacts");
        Files.createDirectories(dir);
        Path out = dir.resolve("t4-generation-baseline.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        System.out.println("[bench] wrote " + out.toAbsolutePath()
                + " allMeetP95=" + allMeet + " maxP95=" + round3(maxP95) + "ms");

        assertFalse(scenarios.isEmpty(), "基准应覆盖所有输入与场景");
        assertTrue(Files.exists(out), "基线文件应写出");
    }

    /** CPU 型号：Windows 用 PROCESSOR_IDENTIFIER，Linux 读 /proc/cpuinfo；不派生子进程，取不到则回退。 */
    private static String cpuModel() {
        String id = System.getenv("PROCESSOR_IDENTIFIER");
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("model name") || lower.startsWith("hardware")) {
                        int idx = line.indexOf(':');
                        if (idx >= 0) {
                            String v = line.substring(idx + 1).trim();
                            if (!v.isEmpty()) {
                                return v;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 落到 unknown 回退
            }
        }
        return "unknown (" + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ")";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
