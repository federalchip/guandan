package com.guandan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.GuandanApplication;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.engine.GameEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real loopback HTTP request/response measurements, separate from pure engine timings. */
@Tag("performance")
@SpringBootTest(classes = GuandanApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class T10HttpPerformanceTest {
    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final int WARMUP = 10;
    private static final int MEASURE = 100;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired InMemoryGameStore store;
    @Autowired GameService service;
    @Autowired ObjectMapper json;

    private record Ref(String id, String token, long seed, GameState state) { }
    private record Timed(HttpResponse<byte[]> response, double ms) { }

    @Test
    void measureLegalActionsAndFreshCommandBatches() throws Exception {
        List<Double> legalHttp = new ArrayList<>();
        List<Double> legalGenerate = new ArrayList<>();
        List<Double> legalSerialize = new ArrayList<>();
        List<Integer> legalBytes = new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        List<Double> normalHttp = new ArrayList<>();
        List<Double> longHttp = new ArrayList<>();
        List<Integer> normalBytes = new ArrayList<>();
        List<Integer> longBytes = new ArrayList<>();

        for (int i = -WARMUP; i < MEASURE; i++) {
            boolean measured = i >= 0;
            Ref normal = fullDeal(20_000L + i + WARMUP);
            long genStart = System.nanoTime();
            LegalActionsResponse legal = service.legalActions(normal.id(), normal.token());
            double genMs = elapsed(genStart);
            long jsonStart = System.nanoTime();
            byte[] encoded = json.writeValueAsBytes(legal);
            double jsonMs = elapsed(jsonStart);
            Timed legalResult = get(normal, "/legal-actions");
            assertEquals(200, legalResult.response().statusCode());
            assertEquals(encoded.length, legalResult.response().body().length);
            if (measured) {
                legalGenerate.add(genMs);
                legalSerialize.add(jsonMs);
                legalHttp.add(legalResult.ms());
                legalBytes.add(encoded.length);
                candidates.add(legal.moves().size());
            }

            Action single = GameEngine.getLegalActions(normal.state(), 0).stream()
                    .filter(action -> action.cardIds().size() == 1).findFirst().orElseThrow();
            Timed normalResult = postAction(normal, single);
            assertEquals(200, normalResult.response().statusCode(), new String(normalResult.response().body()));
            JsonNode normalBody = json.readTree(normalResult.response().body());
            assertEquals("PLAYING", normalBody.path("state").path("phase").asText());
            assertEquals(0, normalBody.path("state").path("current_player").asInt());
            if (measured) {
                normalHttp.add(normalResult.ms());
                normalBytes.add(normalResult.response().body().length);
            }

            Ref lastHuman = shortFinish(30_000L + i + WARMUP);
            Action finalCard = GameEngine.getLegalActions(lastHuman.state(), 0).stream()
                    .filter(action -> action.cardIds().equals(lastHuman.state().hands().get(0)))
                    .findFirst().orElseThrow();
            Timed longResult = postAction(lastHuman, finalCard);
            assertEquals(200, longResult.response().statusCode(), new String(longResult.response().body()));
            JsonNode longBody = json.readTree(longResult.response().body());
            assertEquals("FINISHED", longBody.path("state").path("phase").asText());
            assertTrue(longBody.path("events").size() > 1);
            if (measured) {
                longHttp.add(longResult.ms());
                longBytes.add(longResult.response().body().length);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generated_at", Instant.now().toString());
        report.put("profile_id", PROFILE.profileId());
        report.put("environment", environment());
        report.put("measurement", "real loopback HTTP (random port), new game/request ID per batch; response fully read; no frontend animation");
        report.put("cache_condition", "no command idempotency hits: every game and request ID is new; legal actions computed on each call");
        report.put("warmup_per_scenario", WARMUP);
        report.put("measured_per_scenario", MEASURE);
        report.put("normal_seed_start", 20_000 + WARMUP);
        report.put("long_seed_start", 30_000 + WARMUP);
        report.put("long_fixture", "seeded 108-card deal: human retains 1 card, its other 26 cards are already discarded, each bot retains 27 cards; each request executes the remaining bot game");
        report.put("legal_actions", Map.of("http_ms", stats(legalHttp), "generation_ms", stats(legalGenerate),
                "serialization_ms", stats(legalSerialize), "response_bytes", statsInt(legalBytes),
                "candidate_count", statsInt(candidates)));
        Map<String, Object> normal = new LinkedHashMap<>();
        normal.put("http_ms", stats(normalHttp));
        normal.put("response_bytes", statsInt(normalBytes));
        normal.put("target_p95_ms", 3000);
        normal.put("meets_target", percentile(normalHttp, 0.95) <= 3000);
        report.put("normal_batch", normal);
        Map<String, Object> longBatch = new LinkedHashMap<>();
        longBatch.put("http_ms", stats(longHttp));
        longBatch.put("response_bytes", statsInt(longBytes));
        longBatch.put("target_max_ms", 10000);
        longBatch.put("meets_target", longHttp.stream().mapToDouble(Double::doubleValue).max().orElseThrow() <= 10000);
        report.put("human_finished_batch", longBatch);
        Path dir = Path.of("artifacts");
        Files.createDirectories(dir);
        Path output = dir.resolve("t10-http-performance.json");
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("[t10-http] wrote " + output.toAbsolutePath());
    }

    private Ref fullDeal(long seed) {
        return create(seed, GameEngine.newGame(seed, PROFILE));
    }

    private Ref shortFinish(long seed) {
        List<List<Integer>> dealt = Deck.deal(seed);
        List<List<Integer>> hands = List.of(List.of(dealt.get(0).get(0)), dealt.get(1), dealt.get(2), dealt.get(3));
        List<Integer> discarded = List.copyOf(dealt.get(0).subList(1, Deck.HAND_SIZE));
        GameState state = new GameState(PROFILE, hands, discarded, 0, null,
                List.of(), Phase.PLAYING, null, 0);
        return create(seed, state);
    }

    private Ref create(long seed, GameState state) {
        String id = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        store.create(id, token, seed, state);
        return new Ref(id, token, seed, state);
    }

    private Timed get(Ref ref, String suffix) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(ref) + suffix))
                .header("Authorization", "Bearer " + ref.token()).GET().build();
        long start = System.nanoTime();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Timed(response, elapsed(start));
    }

    private Timed postAction(Ref ref, Action action) throws Exception {
        Map<String, Object> body = Map.of(
                "request_id", UUID.randomUUID().toString(), "expected_version", ref.state().version(),
                "action", Map.of("kind", "PLAY", "card_ids", action.cardIds(),
                        "interpretation_key", action.interpretationKey()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(ref) + "/actions"))
                .header("Authorization", "Bearer " + ref.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        long start = System.nanoTime();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Timed(response, elapsed(start));
    }

    private String url(Ref ref) {
        return "http://127.0.0.1:" + port + "/api/v1/games/" + ref.id();
    }

    private static double elapsed(long start) {
        return (System.nanoTime() - start) / 1e6;
    }

    private static Map<String, Object> environment() {
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

    private static Map<String, Object> stats(List<Double> values) {
        return Map.of("p50_ms", round(percentile(values, .50)), "p95_ms", round(percentile(values, .95)),
                "max_ms", round(values.stream().mapToDouble(Double::doubleValue).max().orElseThrow()));
    }

    private static Map<String, Object> statsInt(List<Integer> values) {
        List<Double> doubles = values.stream().map(Integer::doubleValue).toList();
        return Map.of("p50", percentile(doubles, .50), "p95", percentile(doubles, .95),
                "max", values.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    private static double percentile(List<Double> values, double q) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return sorted[Math.max(0, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
