package com.guandan.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.bots.BotSeeds;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameResult;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.PlayerView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 冒烟与整局耗时初测（计划 §T6）：四个合法随机机器人自动对局。seed=10000 预热一局，
 * 再完整运行 seed=0..19 共 20 局；每步用 {@link TurnFixtures} 校验守恒（四家手牌 + discarded = 108、
 * trick ⊆ discarded）与回合/动作合法性，每局最多 512 个动作。输出 artifacts/m2-smoke.json：
 * 逐局 seed/结果/动作数/候选生成累计耗时/纯引擎+策略整局耗时，样本 p50/p95/max，以及含不变量检查
 * 与日志的总壁钟时间。纯引擎+策略，无 HTTP/动画，不作为 T10 的 HTTP 批次验收；10,000 局为小样本均值推算。
 */
@Tag("slow")
class M2SmokeTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final int MAX_ACTIONS = 512;
    private static final long WARMUP_SEED = 10000L;
    private static final int GAMES = 20;

    @Test
    void seededGamesAndTimings() throws Exception {
        runGame(WARMUP_SEED);

        List<Map<String, Object>> gameRows = new ArrayList<>();
        double[] computeMs = new double[GAMES];
        long totalWallStart = System.nanoTime();
        for (int i = 0; i < GAMES; i++) {
            GameRecord rec = runGame(i);
            gameRows.add(rec.toMap());
            computeMs[i] = rec.computeMs();
            System.out.printf("[m2] seed=%d outcome=%s winner_team=%d actions=%d gen=%.2fms compute=%.2fms wall=%dms%n",
                    rec.seed(), rec.outcome(), rec.winnerTeam(), rec.actions(),
                    rec.candidateGenMs(), rec.computeMs(), rec.wallMs());
        }
        long totalWallMs = (System.nanoTime() - totalWallStart) / 1_000_000;

        double[] sorted = computeMs.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("p50_ms", round3(percentile(sorted, 0.50)));
        stats.put("p95_ms", round3(percentile(sorted, 0.95)));
        stats.put("max_ms", round3(sorted[sorted.length - 1]));
        stats.put("mean_ms", round3(mean));

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("cpu_model", cpuModel());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        env.put("jdk", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        env.put("vm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        env.put("processors", Runtime.getRuntime().availableProcessors());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("profile_id", PROFILE.profileId());
        report.put("generated_at", Instant.now().toString());
        report.put("warmup_seed", WARMUP_SEED);
        report.put("games_run", GAMES);
        report.put("max_actions_guard", MAX_ACTIONS);
        report.put("environment", env);
        report.put("games", gameRows);
        report.put("compute_ms_stats", stats);
        report.put("total_wall_ms_incl_checks_and_logging", totalWallMs);
        report.put("estimate_10000_games_compute_seconds", round3(mean * 10000 / 1000.0));
        report.put("note", "M2 初测：纯引擎+策略，无 HTTP/动画，不含 T10 的 HTTP 批次验收；"
                + "10000 局为 20 局小样本均值推算，仅供参考。每局均通过 108 张守恒、trick⊆discarded、"
                + "回合与动作合法性检查，动作数受 512 上限保护。");

        Path dir = Path.of("artifacts");
        Files.createDirectories(dir);
        Path out = dir.resolve("m2-smoke.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
        System.out.println("[m2] wrote " + out.toAbsolutePath()
                + " compute p50=" + stats.get("p50_ms") + "ms p95=" + stats.get("p95_ms")
                + "ms max=" + stats.get("max_ms") + "ms totalWall=" + totalWallMs + "ms");
        assertTrue(Files.exists(out), "m2-smoke.json 应写出");
    }

    /** 完整跑一局四机器人对局，逐步校验不变量，返回计时与结果记录。 */
    private GameRecord runGame(long seed) {
        GameState state = GameEngine.newGame(seed, PROFILE);
        int actions = 0;
        long candidateGenNanos = 0;
        long computeNanos = 0;
        long wallStart = System.nanoTime();

        while (state.phase() == Phase.PLAYING) {
            assertTrue(actions < MAX_ACTIONS, "超过 " + MAX_ACTIONS + " 动作保护: seed=" + seed);
            int cp = state.currentPlayer();
            assertNotNull(cp, "PLAYING 阶段必有当前玩家");

            long s0 = System.nanoTime();
            List<Action> legal = GameEngine.getLegalActions(state, cp);
            long s1 = System.nanoTime();
            PlayerView view = PlayerViewFactory.getPlayerView(state, cp);
            long botSeed = BotSeeds.derive(seed, state.version(), cp);
            Action action = RandomLegalPolicy.chooseAction(view, legal, new SplittableRandom(botSeed));
            EngineOutcome out = GameEngine.applyAction(state, cp, action);
            long s2 = System.nanoTime();

            candidateGenNanos += (s1 - s0);
            computeNanos += (s2 - s0);

            assertTrue(legal.contains(action), "机器人动作必须在供给的合法动作中: seed=" + seed);
            state = out.state();
            actions++;

            TurnFixtures.assertConservation(state);
            TurnFixtures.assertFinishedNeverCurrent(state);
        }
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

        assertEquals(Phase.FINISHED, state.phase(), "对局应结束: seed=" + seed);
        assertNull(state.currentPlayer(), "终态 current_player 为 null");
        GameResult result = state.result();
        assertNotNull(result, "终态有结算结果");
        assertTrue(result.winnerTeam() == 0 || result.winnerTeam() == 1, "winner_team∈{0,1}");
        Set<Integer> placed = new HashSet<>();
        for (List<Integer> g : result.placementGroups()) {
            placed.addAll(g);
        }
        assertEquals(Set.of(0, 1, 2, 3), placed, "四家均有名次");
        TurnFixtures.assertConservation(state);

        return new GameRecord(seed, result.outcome().name(), result.winnerTeam(), actions,
                candidateGenNanos / 1e6, computeNanos / 1e6, wallMs);
    }

    private record GameRecord(long seed, String outcome, int winnerTeam, int actions,
                              double candidateGenMs, double computeMs, long wallMs) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seed", seed);
            m.put("outcome", outcome);
            m.put("winner_team", winnerTeam);
            m.put("actions", actions);
            m.put("candidate_gen_ms", round3(candidateGenMs));
            m.put("compute_ms", round3(computeMs));
            m.put("wall_ms", wallMs);
            return m;
        }
    }

    private static double percentile(double[] sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

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
}
