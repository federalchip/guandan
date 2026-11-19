package com.guandan.service;

import com.guandan.api.dto.ActionRequest;
import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.GameView;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.MoveView;
import com.guandan.api.dto.ResolveRequest;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.engine.GameEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 A 异步命令服务层测试：手动执行器确定性控制"处理中→提交/失败"，不依赖真实等待。 */
class AsyncCommandTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final long SEED = 42L;

    private final ManualExecutor executor = new ManualExecutor();

    private GameService asyncService(InMemoryGameStore store) {
        return new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction, GameEngine::applyAction,
                executor, new AsyncCommandConfig(true, 12_000L, 500L));
    }

    static final class ManualExecutor implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void drain() { Runnable r; while ((r = queue.poll()) != null) r.run(); }
    }

    // ---- 登记与处理中 ----

    @Test
    void test_async_submit_registers_pending_and_keeps_old_snapshot() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        CommandRequest cmd = playCommand(g.state().version(), single);

        CommandOutcome outcome = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        CommandOutcome.Pending pending = assertInstanceOf(CommandOutcome.Pending.class, outcome);
        assertEquals(cmd.requestId(), pending.requestId());
        assertEquals(500L, pending.pollAfterMillis());
        assertEquals(1, executor.queue.size(), "恰登记一个工作任务");
        GameRecord rec = store.get(g.gameId());
        assertEquals(0L, rec.state.version(), "处理中对外仍是旧快照");
        assertEquals(27, rec.state.hands().get(0).size(), "真人牌未扣");
        assertEquals(cmd.requestId(), rec.currentJob.requestId());
        GameView view = svc.getGame(g.gameId(), g.sessionToken());
        assertEquals(cmd.requestId(), view.processingRequestId());
        assertEquals(0L, view.version());
        assertInstanceOf(CommandOutcome.Pending.class,
                svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId()));
    }

    @Test
    void test_async_same_id_same_body_while_pending_registers_once() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));

        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);
        CommandOutcome again = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        assertInstanceOf(CommandOutcome.Pending.class, again);
        assertEquals(cmd.requestId(), ((CommandOutcome.Pending) again).requestId());
        assertEquals(1, executor.queue.size(), "同 ID 同体处理中不重复登记");
    }

    @Test
    void test_async_same_id_different_body_while_pending_is_reused() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        long v0 = g.state().version();
        List<MoveView> moves = svc.legalActions(g.gameId(), g.sessionToken()).moves();
        MoveView a = moves.get(0);
        MoveView b = moves.stream().filter(m -> !m.cardIds().equals(a.cardIds())).findFirst().orElseThrow();
        String reqId = UUID.randomUUID().toString();
        svc.submitCommand(g.gameId(), g.sessionToken(),
                new CommandRequest(reqId, v0, new ActionRequest("PLAY", a.cardIds(), a.interpretationKey())));

        GameRuleException ex = assertThrows(GameRuleException.class, () -> svc.submitCommand(g.gameId(),
                g.sessionToken(),
                new CommandRequest(reqId, v0, new ActionRequest("PLAY", b.cardIds(), b.interpretationKey()))));
        assertEquals("REQUEST_ID_REUSED", ex.code());
        assertEquals(1, executor.queue.size());
    }

    @Test
    void test_async_different_id_while_pending_is_game_busy() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        CommandRequest first = playCommand(g.state().version(), single);
        svc.submitCommand(g.gameId(), g.sessionToken(), first);
        // 处理中 legal-actions 会 GAME_BUSY，第二命令复用登记前取到的动作（不同 request_id 即可）
        CommandRequest other = playCommand(g.state().version(), single);
        assertNotEquals(first.requestId(), other.requestId());

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submitCommand(g.gameId(), g.sessionToken(), other));
        assertEquals("GAME_BUSY", ex.code());
        assertEquals(first.requestId(), ex.details().get("request_id"), "details 只含处理中 request_id 与等待建议");
        assertEquals(1, executor.queue.size());
    }

    @Test
    void test_async_version_conflict_precedes_registration() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        CommandRequest cmd = playCommand(g.state().version() + 5, single);

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertEquals("VERSION_CONFLICT", ex.code());
        assertNull(store.get(g.gameId()).currentJob, "版本冲突不登记任务");
        assertTrue(executor.queue.isEmpty());
    }

    @Test
    void test_async_illegal_human_action_rejected_synchronously() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        int opponentCard = Deck.deal(SEED).get(1).get(0);
        CommandRequest cmd = new CommandRequest(UUID.randomUUID().toString(), g.state().version(),
                new ActionRequest("PLAY", List.of(opponentCard), null));

        assertThrows(GameRuleException.class, () -> svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertNull(store.get(g.gameId()).currentJob, "非法真人动作同步拒绝，不登记");
        assertTrue(executor.queue.isEmpty());
        assertEquals(0L, store.get(g.gameId()).state.version());
    }

    // ---- 原子提交 ----

    @Test
    void test_async_worker_commits_once_atomically() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        executor.drain();

        CommandOutcome outcome = svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId());
        CommandOutcome.Completed completed = assertInstanceOf(CommandOutcome.Completed.class, outcome);
        CommandResponse cr = completed.response();
        assertEquals(cmd.requestId(), cr.requestId());
        assertEquals(1L, cr.acceptedVersion(), "accepted_version 为真人动作后版本");
        assertTrue(cr.state().version() > 0L);
        assertEquals(1L, cr.events().get(0).seq());
        for (int i = 1; i < cr.events().size(); i++) {
            assertEquals(cr.events().get(i - 1).seq() + 1, cr.events().get(i).seq());
        }
        GameRecord rec = store.get(g.gameId());
        assertNull(rec.currentJob, "提交后释放任务占位");
        assertEquals(cr.state().version(), rec.state.version());
        assertEquals(1L + cr.events().size(), rec.nextSeq);
        assertEquals(1, rec.idempotency.size());
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
        // 同 ID 重试命中幂等缓存，返回原收据，不再执行
        CommandOutcome retry = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);
        assertEquals(cr.acceptedVersion(), assertInstanceOf(CommandOutcome.Completed.class, retry).response().acceptedVersion());
        assertTrue(executor.queue.isEmpty());
        // 再次 drain 无任务可跑，状态不变
        long version = rec.state.version();
        executor.drain();
        assertEquals(version, rec.state.version());
    }

    // ---- 终态失败 ----

    @Test
    void test_async_bot_failure_marks_failed_without_commit() {
        InMemoryGameStore store = new InMemoryGameStore();
        BotPolicy throwing = (view, legal, rng) -> { throw new IllegalStateException("bot boom"); };
        GameService svc = new GameService(store, () -> SEED, throwing, GameEngine::applyAction,
                executor, new AsyncCommandConfig(true, 12_000L, 500L));
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        executor.drain();

        CommandOutcome.Failed failed = assertInstanceOf(CommandOutcome.Failed.class,
                svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId()));
        assertEquals("INTERNAL_ERROR", failed.code());
        GameRecord rec = store.get(g.gameId());
        assertEquals(0L, rec.state.version(), "失败整批不提交");
        assertTrue(rec.events.isEmpty());
        assertEquals(1L, rec.nextSeq);
        assertNull(rec.currentJob);
        assertTrue(rec.idempotency.isEmpty());
        // 同 ID 终态失败幂等返回，不再执行
        CommandOutcome retry = svc.submitCommand(g.gameId(), g.sessionToken(), cmd);
        assertEquals("INTERNAL_ERROR", assertInstanceOf(CommandOutcome.Failed.class, retry).code());
        assertTrue(executor.queue.isEmpty(), "终态失败不重新登记任务");
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
    }

    @Test
    void test_async_step_limit_marks_failed() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameState stuck = stateWithHands(
                List.of(List.of(4), List.of(8), List.of(12), List.of(16)),
                1, null, List.of(), Phase.PLAYING, null, 0L);
        ActionAdvancer noAdvance = (st, player, action) -> new EngineOutcome(stuck, List.of());
        GameService svc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction, noAdvance,
                executor, new AsyncCommandConfig(true, 12_000L, 500L));
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        executor.drain();

        assertEquals("BOT_STEP_LIMIT", assertInstanceOf(CommandOutcome.Failed.class,
                svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId())).code());
        assertEquals(0L, store.get(g.gameId()).state.version());
    }

    // ---- 查询边界与 GAME_BUSY ----

    @Test
    void test_async_command_status_auth_and_not_found() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        CreatedGame other = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        svc.submitCommand(g.gameId(), g.sessionToken(), cmd);

        assertEquals("SESSION_FORBIDDEN", assertThrows(GameRuleException.class,
                () -> svc.commandStatus(g.gameId(), other.sessionToken(), cmd.requestId())).code());
        assertEquals("SESSION_INVALID", assertThrows(GameRuleException.class,
                () -> svc.commandStatus(g.gameId(), null, cmd.requestId())).code());
        assertEquals("COMMAND_NOT_FOUND", assertThrows(GameRuleException.class,
                () -> svc.commandStatus(g.gameId(), g.sessionToken(), UUID.randomUUID().toString())).code());
        assertEquals("GAME_NOT_FOUND", assertThrows(GameRuleException.class,
                () -> svc.commandStatus("missing-game", g.sessionToken(), cmd.requestId())).code());
    }

    @Test
    void test_async_resolve_and_legal_actions_busy_while_pending() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        CreatedGame g = svc.createGame();
        MoveView single = firstSingle(svc.legalActions(g.gameId(), g.sessionToken()));
        svc.submitCommand(g.gameId(), g.sessionToken(), playCommand(g.state().version(), single));

        assertEquals("GAME_BUSY", assertThrows(GameRuleException.class,
                () -> svc.resolve(g.gameId(), g.sessionToken(),
                        new ResolveRequest(g.state().version(), single.cardIds()))).code());
        assertEquals("GAME_BUSY", assertThrows(GameRuleException.class,
                () -> svc.legalActions(g.gameId(), g.sessionToken())).code());
    }

    // ---- 执行器拒绝与模式固定 ----

    @Test
    void test_async_executor_rejection_clears_job_and_allows_retry() {
        InMemoryGameStore store = new InMemoryGameStore();
        ManualExecutor fallback = new ManualExecutor();
        Executor rejecting = r -> { throw new RejectedExecutionException("full"); };
        Executor rejectOnce = new Executor() {
            boolean rejected;
            @Override public void execute(Runnable command) {
                if (!rejected) {
                    rejected = true;
                    rejecting.execute(command);
                }
                fallback.execute(command);
            }
        };
        GameService svc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction,
                GameEngine::applyAction, rejectOnce, new AsyncCommandConfig(true, 12_000L, 500L));
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertEquals("COMMAND_QUEUE_FULL", ex.code());
        assertNull(store.get(g.gameId()).currentJob, "拒绝后不留永久 PENDING");
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
        // 同 ID 原命令可重新登记（此前未执行）
        assertInstanceOf(CommandOutcome.Pending.class, svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
    }

    @Test
    void test_async_mode_fixed_at_creation() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = asyncService(store);
        // 四参 create 的旧式记录 asyncMode=false：即使服务开了异步开关，该局仍走同步
        store.create("g-sync", "t-sync", SEED, GameEngine.newGame(SEED, PROFILE));
        GameService syncSvc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction,
                GameEngine::applyAction, executor, AsyncCommandConfig.syncDefaults());
        CommandRequest cmd = playCommand(0L, firstSingle(syncSvc.legalActions("g-sync", "t-sync")));
        assertInstanceOf(CommandOutcome.Completed.class, svc.submitCommand("g-sync", "t-sync", cmd));
        assertTrue(executor.queue.isEmpty(), "同步局不使用执行器");
    }

    @Test
    void test_sync_service_submit_command_returns_completed_immediately() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = new GameService(store, () -> SEED, RandomLegalPolicy::chooseAction,
                GameEngine::applyAction, executor, AsyncCommandConfig.syncDefaults());
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        CommandOutcome.Completed completed = assertInstanceOf(CommandOutcome.Completed.class,
                svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        assertEquals(1L, completed.response().acceptedVersion());
        assertTrue(executor.queue.isEmpty());
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
    }

    // ---- 辅助（与 GameServiceTest 相同形态） ----

    private static MoveView firstSingle(LegalActionsResponse la) {
        return la.moves().stream().filter(m -> "SINGLE".equals(m.kind())).findFirst().orElseThrow();
    }

    private static CommandRequest playCommand(long version, MoveView mv) {
        return new CommandRequest(UUID.randomUUID().toString(), version,
                new ActionRequest("PLAY", mv.cardIds(), mv.interpretationKey()));
    }

    private static GameState stateWithHands(List<List<Integer>> hands, Integer currentPlayer,
                                            com.guandan.domain.model.Trick trick, List<Integer> finishOrder,
                                            Phase phase, com.guandan.domain.model.GameResult result, long version) {
        Set<Integer> union = new HashSet<>();
        for (List<Integer> h : hands) union.addAll(h);
        List<Integer> discarded = new ArrayList<>();
        for (int id = 0; id < Deck.TOTAL_CARDS; id++) {
            if (!union.contains(id)) discarded.add(id);
        }
        return new GameState(PROFILE, hands, discarded, currentPlayer, trick, finishOrder, phase, result, version);
    }
}
