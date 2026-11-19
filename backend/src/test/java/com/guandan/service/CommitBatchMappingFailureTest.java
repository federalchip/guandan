package com.guandan.service;

import com.guandan.api.dto.ActionRequest;
import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.MoveView;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.engine.GameEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 回归（map-before-mutate）：响应构建（DTO 映射）失败时不得先行改动记录。
 * 异步路径须落终态 FAILED 并释放任务占位，牌局不被永久困在 PENDING；同步路径须整批不提交。
 * 通过覆写包私有 {@link GameService#buildResponse} 注入映射故障，不引入生产配置。
 */
class CommitBatchMappingFailureTest {

    private static final long SEED = 42L;

    static final class ManualExecutor implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void drain() { Runnable r; while ((r = queue.poll()) != null) r.run(); }
    }

    /** 仅在响应构建 seam 抛错，其余编排（登记、批次、失败落账）走真实 GameService。 */
    static final class MappingFailsService extends GameService {
        MappingFailsService(InMemoryGameStore store, Executor executor, AsyncCommandConfig config) {
            super(store, () -> SEED, RandomLegalPolicy::chooseAction, GameEngine::applyAction, executor, config);
        }

        @Override
        CommandResponse buildResponse(GameRecord rec, String requestId, BotBatchRunner.BatchResult r) {
            throw new IllegalStateException("mapping boom");
        }
    }

    @Test
    void test_async_mapping_failure_keeps_record_and_marks_failed_not_stranded() {
        InMemoryGameStore store = new InMemoryGameStore();
        ManualExecutor executor = new ManualExecutor();
        GameService svc = new MappingFailsService(store, executor, new AsyncCommandConfig(true, 12_000L, 500L));
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        assertInstanceOf(CommandOutcome.Pending.class, svc.submitCommand(g.gameId(), g.sessionToken(), cmd));
        GameRecord rec = store.get(g.gameId());

        executor.drain();

        assertEquals(0L, rec.state.version(), "映射失败不得推进状态版本");
        assertTrue(rec.events.isEmpty(), "映射失败不得写入事件");
        assertEquals(1L, rec.nextSeq, "映射失败不得推进事件序号");
        assertTrue(rec.idempotency.isEmpty(), "映射失败不得留下成功收据");
        assertNull(rec.currentJob, "终态失败必须释放任务占位");
        CommandOutcome.Failed failed = assertInstanceOf(CommandOutcome.Failed.class,
                svc.commandStatus(g.gameId(), g.sessionToken(), cmd.requestId()));
        assertEquals("INTERNAL_ERROR", failed.code());
        assertNull(svc.getGame(g.gameId(), g.sessionToken()).processingRequestId());
        // 新命令（不同 request_id）仍可登记：牌局未被永久困在 PENDING
        CommandRequest next = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));
        assertNotEquals(cmd.requestId(), next.requestId());
        assertInstanceOf(CommandOutcome.Pending.class, svc.submitCommand(g.gameId(), g.sessionToken(), next));
    }

    @Test
    void test_sync_mapping_failure_leaves_record_untouched() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService svc = new MappingFailsService(store, Runnable::run, AsyncCommandConfig.syncDefaults());
        CreatedGame g = svc.createGame();
        CommandRequest cmd = playCommand(g.state().version(),
                firstSingle(svc.legalActions(g.gameId(), g.sessionToken())));

        assertThrows(IllegalStateException.class, () -> svc.submitCommand(g.gameId(), g.sessionToken(), cmd));

        GameRecord rec = store.get(g.gameId());
        assertEquals(0L, rec.state.version(), "同步映射失败整批不提交");
        assertTrue(rec.events.isEmpty());
        assertEquals(1L, rec.nextSeq);
        assertTrue(rec.idempotency.isEmpty());
        assertNull(rec.currentJob);
    }

    private static MoveView firstSingle(LegalActionsResponse la) {
        return la.moves().stream().filter(m -> "SINGLE".equals(m.kind())).findFirst().orElseThrow();
    }

    private static CommandRequest playCommand(long version, MoveView mv) {
        return new CommandRequest(UUID.randomUUID().toString(), version,
                new ActionRequest("PLAY", mv.cardIds(), mv.interpretationKey()));
    }
}