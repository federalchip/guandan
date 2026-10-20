package com.guandan.service;

import com.guandan.api.dto.ActionRequest;
import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.GameView;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.MoveView;
import com.guandan.api.dto.ResolveRequest;
import com.guandan.api.dto.ResolveResponse;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameResult;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.Outcome;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.Trick;
import com.guandan.engine.GameEngine;
import com.guandan.rules.MoveClassifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T7 服务层单元测试：注入固定 seed、内存存储与策略，覆盖创建/解析/出牌/过牌/刷新/结束拒绝、
 * 幂等、并发版本冲突、机器人异常与步数保护整批回滚、重启清空与新局保留旧收据、会话与令牌边界。
 */
class GameServiceTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final long SEED = 42L;

    private static GameService service(InMemoryGameStore store) {
        return new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction, GameEngine::applyAction);
    }

    // ---- 创建 / 基本流程 ----

    @Test
    void test_create_game_consistent_ids_and_hides_opponents() {
        InMemoryGameStore store = new InMemoryGameStore();
        CreatedGame g = service(store).createGame();
        assertEquals(g.gameId(), g.state().gameId(), "两处 game_id 一致");
        assertNotNull(g.sessionToken());
        GameView v = g.state();
        assertEquals(27, v.ownHand().size());
        assertEquals(List.of(27, 27, 27, 27), v.remainingCounts());
        assertEquals(0, v.currentPlayer());
        assertTrue(v.isLead());
        assertFalse(v.canPass(), "领出不可过牌");
        assertEquals(0L, v.version());
        assertEquals("PLAYING", v.phase());

        List<List<Integer>> dealt = Deck.deal(SEED);
        Set<Integer> opponents = new HashSet<>();
        for (int p = 1; p < 4; p++) {
            opponents.addAll(dealt.get(p));
        }
        for (var c : v.ownHand()) {
            assertEquals(dealt.get(0).contains(c.cardId()), true, "只含本人手牌");
            assertFalse(opponents.contains(c.cardId()), "不含对手手牌");
        }
    }

    @Test
    void test_resolve_play_and_refresh_snapshot() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        long v0 = g.state().version();

        LegalActionsResponse la = svc.legalActions(id, token);
        assertEquals(v0, la.version());
        assertFalse(la.canPass());
        MoveView single = firstSingle(la);

        ResolveResponse rr = svc.resolve(id, token, new ResolveRequest(v0, single.cardIds()));
        assertEquals(v0, rr.version());
        assertFalse(rr.choices().isEmpty(), "解析返回声明");

        CommandResponse cr = svc.submit(id, token, playCommand(v0, single));
        assertEquals(v0 + 1, cr.acceptedVersion(), "accepted_version 为真人动作后的版本");
        assertFalse(cr.events().isEmpty(), "命令返回公开事件");
        assertTrue(cr.events().get(0).seq() >= 1);

        GameView refreshed = svc.getGame(id, token);
        assertEquals(cr.state().version(), refreshed.version(), "刷新快照与命令结果一致");
        assertEquals(26, refreshed.ownHand().size(), "真人出一张后剩 26");
        int lastClose = -1;
        for (int i = 0; i < cr.events().size(); i++) {
            if ("TRICK_CLOSED".equals(cr.events().get(i).eventType())) lastClose = i;
        }
        assertEquals(cr.events().subList(lastClose + 1, cr.events().size()), refreshed.roundEvents(),
                "刷新后应恢复尚未结束的本轮公开事件");
        assertEquals(refreshed.roundEvents(), cr.state().roundEvents(),
                "命令快照与刷新快照应包含相同的本轮公开事件");
    }

    @Test
    void test_pass_allowed_when_following() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        Move single5 = MoveClassifier.classify(CardIds.parse("S5@0"), PROFILE).get(0);
        GameState follow = stateWithHands(
                List.of(CardIds.parse("S3@0 H4@0"), CardIds.parse("H6@0"),
                        CardIds.parse("D7@0"), CardIds.parse("C8@0")),
                0, new Trick(1, single5, Set.of(0, 2, 3)), List.of(), Phase.PLAYING, null, 5L);
        store.create("g-pass", "t-pass", SEED, follow);

        LegalActionsResponse la = svc.legalActions("g-pass", "t-pass");
        assertTrue(la.canPass(), "跟牌可过");

        CommandResponse cr = svc.submit("g-pass", "t-pass", passCommand(5L));
        assertEquals(6L, cr.acceptedVersion(), "过牌使 version+1");
    }

    @Test
    void test_reject_action_after_finish() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        GameResult result = new GameResult(0, List.of(List.of(0), List.of(1), List.of(2), List.of(3)),
                Outcome.TEAMMATE_THIRD);
        GameState finished = stateWithHands(
                List.of(List.of(), List.of(), List.of(), CardIds.parse("C8@0 C9@0")),
                null, null, List.of(0, 1, 2), Phase.FINISHED, result, 10L);
        store.create("g-end", "t-end", SEED, finished);

        LegalActionsResponse la = svc.legalActions("g-end", "t-end");
        assertTrue(la.moves().isEmpty(), "结束后 moves 为空");
        assertFalse(la.canPass(), "结束后 can_pass=false");

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submit("g-end", "t-end", passCommand(10L)));
        assertEquals("GAME_FINISHED", ex.code());
        assertEquals("FINISHED", svc.getGame("g-end", "t-end").phase());
    }

    @Test
    void test_resolve_accepts_noncanonical_identical_copy() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        int canonical = CardIds.parseOne("S3@0");   // 4
        int copy = CardIds.parseOne("S3@1");        // 58（生成器代表为最小 id 4）
        GameState st = stateWithHands(
                List.of(List.of(canonical, copy, CardIds.parseOne("H4@0")),
                        CardIds.parse("S5@0"), CardIds.parse("S6@0"), CardIds.parse("S7@0")),
                0, null, List.of(), Phase.PLAYING, null, 1L);
        store.create("g-copy", "t-copy", SEED, st);

        ResolveResponse rr = svc.resolve("g-copy", "t-copy", new ResolveRequest(1L, List.of(copy)));
        assertEquals(1, rr.choices().size(), "非规范副本仍可解析为单张");

        MoveView pick = rr.choices().get(0);
        assertEquals(List.of(copy), pick.cardIds(), "解析保留真人所选实体");
        svc.submit("g-copy", "t-copy", playCommand(1L, pick));
        List<Integer> hand0 = store.get("g-copy").state.hands().get(0);
        assertFalse(hand0.contains(copy), "扣除所选副本实体");
        assertTrue(hand0.contains(canonical), "保留未被选中的相同牌面");
    }

    // ---- 幂等与并发 ----

    @Test
    void test_repeat_request_does_not_apply_twice() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        MoveView single = firstSingle(svc.legalActions(id, token));
        CommandRequest cmd = playCommand(g.state().version(), single);

        CommandResponse first = svc.submit(id, token, cmd);
        long versionAfter = first.state().version();
        CommandResponse repeat = svc.submit(id, token, cmd);

        assertEquals(first.acceptedVersion(), repeat.acceptedVersion(), "幂等命中返回原 accepted_version");
        assertEquals(versionAfter, repeat.state().version(), "重复请求不再推进");
        assertEquals(versionAfter, svc.getGame(id, token).version(), "状态未二次扣牌");
    }

    @Test
    void test_same_request_id_different_body_is_rejected() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        long v0 = g.state().version();
        String reqId = UUID.randomUUID().toString();
        List<MoveView> moves = svc.legalActions(id, token).moves();
        MoveView a = moves.get(0);
        MoveView b = moves.stream().filter(m -> !m.cardIds().equals(a.cardIds())).findFirst().orElseThrow();

        svc.submit(id, token, new CommandRequest(reqId, v0,
                new ActionRequest("PLAY", a.cardIds(), a.interpretationKey())));
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submit(id, token, new CommandRequest(reqId, v0,
                        new ActionRequest("PLAY", b.cardIds(), b.interpretationKey()))));
        assertEquals("REQUEST_ID_REUSED", ex.code(), "同 id 异体先于版本检查被拒");
    }

    @Test
    void test_same_request_id_omitted_key_vs_string_null_is_rejected() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        long v0 = g.state().version();
        List<Integer> cards = firstSingle(svc.legalActions(id, token)).cardIds();
        String reqId = UUID.randomUUID().toString();

        // 唯一声明可省略 interpretation_key；第一次成功并留下收据
        svc.submit(id, token, new CommandRequest(reqId, v0, new ActionRequest("PLAY", cards, null)));
        // 同 request_id、把 interpretation_key 显式传成字符串 "null"：请求体不同，必须 409
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submit(id, token, new CommandRequest(reqId, v0, new ActionRequest("PLAY", cards, "null"))));
        assertEquals("REQUEST_ID_REUSED", ex.code(), "省略键(null)与字符串\"null\"是不同请求体");
    }

    @Test
    void test_cached_fingerprint_immune_to_caller_list_mutation() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        long v0 = g.state().version();
        MoveView single = firstSingle(svc.legalActions(id, token));
        String key = single.interpretationKey();
        String reqId = UUID.randomUUID().toString();

        List<Integer> mutable = new ArrayList<>(single.cardIds());
        CommandResponse first = svc.submit(id, token,
                new CommandRequest(reqId, v0, new ActionRequest("PLAY", mutable, key)));

        // 调用方提交后改动原列表：缓存指纹应为独立不可变快照，不受影响
        mutable.add(999);

        // 用同 request_id 重试内容相同的原请求体，应幂等命中返回原收据，而非误报 REQUEST_ID_REUSED
        List<Integer> retryCards = new ArrayList<>(single.cardIds());
        CommandResponse retry = svc.submit(id, token,
                new CommandRequest(reqId, v0, new ActionRequest("PLAY", retryCards, key)));
        assertEquals(first.acceptedVersion(), retry.acceptedVersion(), "缓存指纹为快照，重试原命令仍幂等命中");
    }

    @Test
    void test_two_commands_same_version_only_one_commits() throws Exception {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        long v0 = g.state().version();
        List<MoveView> moves = svc.legalActions(id, token).moves();
        MoveView a = moves.get(0);
        MoveView b = moves.stream().filter(m -> !m.cardIds().equals(a.cardIds())).findFirst().orElseThrow();
        CommandRequest c1 = new CommandRequest(UUID.randomUUID().toString(), v0,
                new ActionRequest("PLAY", a.cardIds(), a.interpretationKey()));
        CommandRequest c2 = new CommandRequest(UUID.randomUUID().toString(), v0,
                new ActionRequest("PLAY", b.cardIds(), b.interpretationKey()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier gate = new CyclicBarrier(2);
        Callable<Object> t1 = () -> {
            gate.await();
            return svc.submit(id, token, c1);
        };
        Callable<Object> t2 = () -> {
            gate.await();
            return svc.submit(id, token, c2);
        };
        Future<Object> f1 = pool.submit(t1);
        Future<Object> f2 = pool.submit(t2);
        int ok = 0;
        int conflict = 0;
        for (Future<Object> f : List.of(f1, f2)) {
            try {
                f.get();
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof GameRuleException gre && "VERSION_CONFLICT".equals(gre.code())) {
                    conflict++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok, "恰一条提交成功");
        assertEquals(1, conflict, "另一条版本冲突");
    }

    // ---- 回滚 ----

    @Test
    void test_bot_exception_rolls_back_whole_batch() {
        InMemoryGameStore store = new InMemoryGameStore();
        BotPolicy throwing = (view, legal, rng) -> {
            throw new IllegalStateException("bot boom");
        };
        GameService svc = new GameService(store, () -> SEED, throwing, GameEngine::applyAction);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        long v0 = g.state().version();
        MoveView single = firstSingle(svc.legalActions(id, token));

        assertThrows(IllegalStateException.class, () -> svc.submit(id, token, playCommand(v0, single)));

        GameRecord rec = store.get(id);
        assertEquals(v0, rec.state.version(), "回滚后 version 不变");
        assertEquals(27, rec.state.hands().get(0).size(), "真人牌未被扣除");
        assertTrue(rec.events.isEmpty(), "无部分事件提交");
        assertEquals(1L, rec.nextSeq, "事件序号未推进");
        assertTrue(rec.idempotency.isEmpty(), "幂等账本无部分提交");
    }

    @Test
    void test_step_limit_rolls_back_whole_batch() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameState stuck = stateWithHands(
                List.of(List.of(4), List.of(8), List.of(12), List.of(16)),
                1, null, List.of(), Phase.PLAYING, null, 0L);
        ActionAdvancer noAdvance = (st, player, action) -> new EngineOutcome(stuck, List.of());
        GameService svc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction, noAdvance);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();
        long v0 = g.state().version();
        MoveView single = firstSingle(svc.legalActions(id, token));

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submit(id, token, playCommand(v0, single)));
        assertEquals("BOT_STEP_LIMIT", ex.code());

        GameRecord rec = store.get(id);
        assertEquals(v0, rec.state.version(), "回滚后 version 不变");
        assertEquals(27, rec.state.hands().get(0).size());
        assertTrue(rec.events.isEmpty());
        assertEquals(1L, rec.nextSeq);
        assertTrue(rec.idempotency.isEmpty());
    }

    // ---- 生命周期 ----

    @Test
    void test_restart_clears_all_game_records() {
        InMemoryGameStore store1 = new InMemoryGameStore();
        GameService svc1 = service(store1);
        CreatedGame g = svc1.createGame();
        svc1.submit(g.gameId(), g.sessionToken(),
                playCommand(g.state().version(), firstSingle(svc1.legalActions(g.gameId(), g.sessionToken()))));

        InMemoryGameStore store2 = new InMemoryGameStore();
        GameService svc2 = service(store2);
        GameRuleException notFound = assertThrows(GameRuleException.class,
                () -> svc2.getGame(g.gameId(), g.sessionToken()));
        assertEquals("GAME_NOT_FOUND", notFound.code(), "重启后旧局 404");
        assertEquals(0, store2.size(), "重启后无任何记录");
    }

    @Test
    void test_new_game_keeps_old_request_receipt() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g1 = svc.createGame();
        CommandRequest cmd = playCommand(g1.state().version(),
                firstSingle(svc.legalActions(g1.gameId(), g1.sessionToken())));
        CommandResponse receipt = svc.submit(g1.gameId(), g1.sessionToken(), cmd);

        CreatedGame g2 = svc.createGame();
        assertNotEquals(g1.gameId(), g2.gameId());

        CommandResponse again = svc.submit(g1.gameId(), g1.sessionToken(), cmd);
        assertEquals(receipt.acceptedVersion(), again.acceptedVersion(), "同进程新局不破坏旧局收据");
        assertEquals(receipt.requestId(), again.requestId());
    }

    // ---- 会话与令牌 ----

    @Test
    void test_session_and_token_boundaries() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        String id = g.gameId();
        String token = g.sessionToken();

        assertEquals("SESSION_INVALID",
                assertThrows(GameRuleException.class, () -> svc.getGame(id, null)).code());
        assertEquals("SESSION_INVALID",
                assertThrows(GameRuleException.class, () -> svc.getGame(id, "not-a-real-token")).code());
        assertEquals("GAME_NOT_FOUND",
                assertThrows(GameRuleException.class, () -> svc.getGame("missing-id", token)).code());

        CreatedGame other = svc.createGame();
        assertEquals("SESSION_FORBIDDEN",
                assertThrows(GameRuleException.class, () -> svc.getGame(id, other.sessionToken())).code());
    }

    @Test
    void test_resolve_wrong_version_conflicts() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.resolve(g.gameId(), g.sessionToken(),
                        new ResolveRequest(g.state().version() + 5, single.cardIds())));
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    // ---- 必填版本字段与错误优先级 ----

    @Test
    void test_command_missing_expected_version_is_schema_invalid() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        // expected_version 缺失(null)：必填，应 SCHEMA_INVALID，不得当作 0 执行推进
        CommandRequest cmd = new CommandRequest(UUID.randomUUID().toString(), null,
                new ActionRequest("PASS", null, null));
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submit(g.gameId(), g.sessionToken(), cmd));
        assertEquals("SCHEMA_INVALID", ex.code());
        assertEquals(g.state().version(), store.get(g.gameId()).state.version(), "未推进牌局");
    }

    @Test
    void test_resolve_missing_expected_version_is_schema_invalid() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.resolve(g.gameId(), g.sessionToken(), new ResolveRequest(null, List.of(4))));
        assertEquals("SCHEMA_INVALID", ex.code());
    }

    @Test
    void test_missing_token_precedes_schema_validation() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = service(store);
        CreatedGame g = svc.createGame();
        // 无令牌 + 非法请求体(非 UUID request_id)：鉴权先于请求体校验 → SESSION_INVALID，而非 SCHEMA_INVALID
        CommandRequest bad = new CommandRequest("not-a-uuid", 0L, new ActionRequest("PASS", null, null));
        assertEquals("SESSION_INVALID",
                assertThrows(GameRuleException.class, () -> svc.submit(g.gameId(), null, bad)).code());
        // resolve 同理：无令牌 + 空 card_ids，先 401
        assertEquals("SESSION_INVALID",
                assertThrows(GameRuleException.class,
                        () -> svc.resolve(g.gameId(), null, new ResolveRequest(0L, List.of()))).code());
    }

    // ---- 辅助 ----

    private static MoveView firstSingle(LegalActionsResponse la) {
        return la.moves().stream().filter(m -> "SINGLE".equals(m.kind())).findFirst().orElseThrow();
    }

    private static CommandRequest playCommand(long version, MoveView mv) {
        return new CommandRequest(UUID.randomUUID().toString(), version,
                new ActionRequest("PLAY", mv.cardIds(), mv.interpretationKey()));
    }

    private static CommandRequest passCommand(long version) {
        return new CommandRequest(UUID.randomUUID().toString(), version,
                new ActionRequest("PASS", null, null));
    }

    private static GameState stateWithHands(List<List<Integer>> hands, Integer currentPlayer, Trick trick,
                                            List<Integer> finishOrder, Phase phase, GameResult result,
                                            long version) {
        // discarded = 四家手牌在 108 张中的补集；trick 的牌不在任何手牌中，故已落在 discarded（trick ⊆ discarded）
        Set<Integer> union = new HashSet<>();
        for (List<Integer> h : hands) {
            union.addAll(h);
        }
        List<Integer> discarded = new ArrayList<>();
        for (int id = 0; id < Deck.TOTAL_CARDS; id++) {
            if (!union.contains(id)) {
                discarded.add(id);
            }
        }
        return new GameState(PROFILE, hands, discarded, currentPlayer, trick, finishOrder, phase, result, version);
    }
}
