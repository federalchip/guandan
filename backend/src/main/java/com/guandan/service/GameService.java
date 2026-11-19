package com.guandan.service;

import com.guandan.api.dto.ActionRequest;
import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.DtoMapper;
import com.guandan.api.dto.EventView;
import com.guandan.api.dto.GameView;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.ResolveRequest;
import com.guandan.api.dto.ResolveResponse;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.Phase;
import com.guandan.engine.GameEngine;
import com.guandan.engine.PlayerViewFactory;
import com.guandan.rules.LegalMoveGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 权威游戏服务：会话、幂等、每局锁与命令批次。所有传输 DTO 在 api/dto 定义；领域引擎只返回
 * PlayerView/GameState。事务顺序（合同 §5）：验证游戏与令牌并取得该局锁 → 幂等命中检查（先于版本
 * 检查）→ 核对 expected_version → 复制工作状态 → 真人动作 + 机器人批次 → 成功一次提交，任何规则/
 * 机器人异常整批回滚。每命令机器人步数上限 512，超限 BOT_STEP_LIMIT 回滚。
 */
@Service
public class GameService {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final int HUMAN_SEAT = 0;
    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final InMemoryGameStore store;
    private final GameSeedSource seedSource;
    private final BotPolicy policy;
    private final ActionAdvancer advancer;
    private final Executor executor;
    private final AsyncCommandConfig commandConfig;
    private final BotBatchRunner runner;

    @Autowired
    public GameService(InMemoryGameStore store, GameSeedSource seedSource, BotPolicy policy,
                       ActionAdvancer advancer, Executor commandExecutor, AsyncCommandConfig commandConfig) {
        this.store = store;
        this.seedSource = seedSource;
        this.policy = policy;
        this.advancer = advancer;
        this.executor = commandExecutor;
        this.commandConfig = commandConfig;
        this.runner = new BotBatchRunner(policy, advancer);
    }

    /** 同步便捷构造器：现有单元测试沿用，异步关闭、执行器为直接执行。 */
    public GameService(InMemoryGameStore store, GameSeedSource seedSource, BotPolicy policy,
                       ActionAdvancer advancer) {
        this(store, seedSource, policy, advancer, Runnable::run, AsyncCommandConfig.syncDefaults());
    }

    public CreatedGame createGame() {
        long seed = seedSource.nextSeed();
        GameState state = GameEngine.newGame(seed, PROFILE);
        String gameId = UUID.randomUUID().toString();
        String sessionToken = UUID.randomUUID().toString();
        store.create(gameId, sessionToken, seed, state, commandConfig.asyncEnabled());
        GameView view = DtoMapper.toGameView(gameId, PlayerViewFactory.getPlayerView(state, HUMAN_SEAT));
        return new CreatedGame(gameId, sessionToken, view);
    }

    public GameView getGame(String gameId, String sessionToken) {
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            return DtoMapper.toGameView(rec.gameId, PlayerViewFactory.getPlayerView(rec.state, HUMAN_SEAT),
                    currentRoundEvents(rec.events), rec.currentJob == null ? null : rec.currentJob.requestId());
        } finally {
            rec.lock.unlock();
        }
    }

    public ResolveResponse resolve(String gameId, String sessionToken, ResolveRequest request) {
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            requireIdle(rec);
            request.validate();
            GameState state = rec.state;
            requireVersion(state, request.expectedVersion());
            List<Move> choices = GameEngine.resolveSelection(state, HUMAN_SEAT, request.cardIds());
            return new ResolveResponse(state.version(), DtoMapper.toMoveViews(choices));
        } finally {
            rec.lock.unlock();
        }
    }

    public LegalActionsResponse legalActions(String gameId, String sessionToken) {
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            requireIdle(rec);
            GameState state = rec.state;
            boolean humanTurn = state.phase() == Phase.PLAYING
                    && state.currentPlayer() != null && state.currentPlayer() == HUMAN_SEAT;
            if (!humanTurn) {
                return new LegalActionsResponse(state.version(), List.of(), false);
            }
            Move previous = state.trick() == null ? null : state.trick().move();
            List<Move> moves = LegalMoveGenerator.generateLegalMoves(
                    state.hands().get(HUMAN_SEAT), previous, PROFILE);
            return new LegalActionsResponse(state.version(), DtoMapper.toMoveViews(moves), state.trick() != null);
        } finally {
            rec.lock.unlock();
        }
    }

    public CommandResponse submit(String gameId, String sessionToken, CommandRequest command) {
        CommandOutcome outcome = submitCommand(gameId, sessionToken, command);
        if (outcome instanceof CommandOutcome.Completed c) {
            return c.response();
        }
        throw new IllegalStateException("命令进入异步处理，请使用 submitCommand/commandStatus");
    }

    public CommandOutcome submitCommand(String gameId, String sessionToken, CommandRequest command) {
        GameRecord rec = authorize(gameId, sessionToken);
        CommandJob job;
        GameState origin;
        Action humanAction;
        long startSeq;
        rec.lock.lock();
        try {
            command.validate();
            List<Object> fingerprint = fingerprint(command);
            CachedCommand cached = rec.idempotency.get(command.requestId());
            if (cached != null) {
                if (cached.fingerprint().equals(fingerprint)) {
                    return new CommandOutcome.Completed(cached.response());
                }
                throw reused(command.requestId());
            }
            CachedFailure failed = rec.failures.get(command.requestId());
            if (failed != null) {
                if (failed.fingerprint().equals(fingerprint)) {
                    return new CommandOutcome.Failed(failed.code(), failed.message());
                }
                throw reused(command.requestId());
            }
            CommandJob pending = rec.currentJob;
            if (pending != null) {
                if (pending.requestId().equals(command.requestId())) {
                    if (pending.fingerprint().equals(fingerprint)) {
                        return new CommandOutcome.Pending(pending.requestId(), commandConfig.pollAfterMillis());
                    }
                    throw reused(command.requestId());
                }
                throw busy(pending);
            }
            requireVersion(rec.state, command.expectedVersion());
            if (!rec.asyncMode) {
                BotBatchRunner.BatchResult r = runner.run(rec.state, toAction(command.action()),
                        rec.gameSeed, rec.nextSeq);
                return new CommandOutcome.Completed(commitBatch(rec, command.requestId(), fingerprint, r));
            }
            humanAction = toAction(command.action());
            // 锁内预校验真人动作：非法同步拒绝，不登记任务；工作任务将从原始快照确定性重放
            advancer.applyAction(rec.state, HUMAN_SEAT, humanAction);
            origin = rec.state;
            startSeq = rec.nextSeq;
            job = new CommandJob(command.requestId(), fingerprint, origin.version(),
                    ++rec.jobGeneration, System.currentTimeMillis() + commandConfig.batchTimeoutMillis());
            rec.currentJob = job;
        } finally {
            rec.lock.unlock();
        }
        try {
            executor.execute(() -> runAsyncBatch(rec, job, origin, humanAction, startSeq));
        } catch (RejectedExecutionException e) {
            rec.lock.lock();
            try {
                if (rec.currentJob == job) {
                    rec.currentJob = null;
                }
            } finally {
                rec.lock.unlock();
            }
            throw new GameRuleException("COMMAND_QUEUE_FULL", "命令队列已满，请稍后重试", Map.of());
        }
        return new CommandOutcome.Pending(job.requestId(), commandConfig.pollAfterMillis());
    }

    public CommandOutcome commandStatus(String gameId, String sessionToken, String requestId) {
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            CommandJob pending = rec.currentJob;
            if (pending != null && pending.requestId().equals(requestId)) {
                return new CommandOutcome.Pending(requestId, commandConfig.pollAfterMillis());
            }
            CachedCommand cached = rec.idempotency.get(requestId);
            if (cached != null) {
                return new CommandOutcome.Completed(cached.response());
            }
            CachedFailure failed = rec.failures.get(requestId);
            if (failed != null) {
                return new CommandOutcome.Failed(failed.code(), failed.message());
            }
            throw new GameRuleException("COMMAND_NOT_FOUND", "命令不存在",
                    Map.of("request_id", String.valueOf(requestId)));
        } finally {
            rec.lock.unlock();
        }
    }

    private void runAsyncBatch(GameRecord rec, CommandJob job, GameState origin, Action humanAction, long startSeq) {
        try {
            BotBatchRunner.BatchResult r = runner.run(origin, humanAction, rec.gameSeed, startSeq);
            rec.lock.lock();
            try {
                if (!ownsJob(rec, job)) {
                    log.warn("丢弃迟到批次: game={} request={}", rec.gameId, job.requestId());
                    return;
                }
                commitBatch(rec, job.requestId(), job.fingerprint(), r);
                rec.currentJob = null;
            } finally {
                rec.lock.unlock();
            }
        } catch (GameRuleException e) {
            failJob(rec, job, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("异步批次内部故障: game={} request={}", rec.gameId, job.requestId(), e);
            failJob(rec, job, "INTERNAL_ERROR", "内部错误");
        }
    }

    /** 任务实例同一即涵盖 request_id 与 generation 核对；另核对起始版本，拒绝迟到写入。 */
    private static boolean ownsJob(GameRecord rec, CommandJob job) {
        return rec.currentJob == job && rec.state.version() == job.startVersion();
    }

    private void failJob(GameRecord rec, CommandJob job, String code, String message) {
        rec.lock.lock();
        try {
            if (!ownsJob(rec, job)) {
                return;
            }
            rec.failures.put(job.requestId(), new CachedFailure(job.fingerprint(), code, message));
            rec.currentJob = null;
        } finally {
            rec.lock.unlock();
        }
    }

    /** 锁内调用：一次提交状态、事件、序号与幂等收据。 */
    private CommandResponse commitBatch(GameRecord rec, String requestId, List<Object> fingerprint,
                                        BotBatchRunner.BatchResult r) {
        rec.state = r.state();
        rec.events.addAll(r.events());
        rec.nextSeq = r.nextSeq();
        GameView finalView = DtoMapper.toGameView(rec.gameId,
                PlayerViewFactory.getPlayerView(r.state(), HUMAN_SEAT), currentRoundEvents(rec.events));
        List<EventView> eventViews = new ArrayList<>(r.events().size());
        for (SequencedEvent se : r.events()) {
            eventViews.add(DtoMapper.toEventView(se.seq(), se.stateVersion(), se.event()));
        }
        CommandResponse response = new CommandResponse(requestId, r.acceptedVersion(),
                finalView, List.copyOf(eventViews));
        rec.idempotency.put(requestId, new CachedCommand(fingerprint, response));
        return response;
    }

    private void requireIdle(GameRecord rec) {
        if (rec.currentJob != null) {
            throw busy(rec.currentJob);
        }
    }

    private GameRuleException busy(CommandJob pending) {
        return new GameRuleException("GAME_BUSY", "本局有命令正在处理",
                Map.of("request_id", pending.requestId(), "poll_after_ms", commandConfig.pollAfterMillis()));
    }

    private static GameRuleException reused(String requestId) {
        return new GameRuleException("REQUEST_ID_REUSED", "request_id 已用于不同请求体",
                Map.of("request_id", requestId));
    }

    // ---- 辅助 ----

    /** 最近一次 TRICK_CLOSED 后的公开事件，供刷新恢复本轮各座位的展示。 */
    private static List<EventView> currentRoundEvents(List<SequencedEvent> events) {
        List<EventView> reversed = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0; i--) {
            SequencedEvent se = events.get(i);
            if (se.event().eventType() == com.guandan.domain.model.EventType.TRICK_CLOSED) break;
            reversed.add(DtoMapper.toEventView(se.seq(), se.stateVersion(), se.event()));
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static Action toAction(ActionRequest ar) {
        return "PASS".equals(ar.kind()) ? Action.pass() : Action.play(ar.cardIds(), ar.interpretationKey());
    }

    /**
     * 请求体指纹（不含 request_id）：按位置逐元素 equals 的结构化列表，区分 null 与字符串 "null"、
     * card_ids 逐张比较，避免字符串拼接的分隔符歧义导致同 id 异体误命中缓存。card_ids 存独立不可变
     * 快照，防止调用方提交后改动原列表污染已缓存指纹。
     */
    private static List<Object> fingerprint(CommandRequest c) {
        ActionRequest a = c.action();
        List<Integer> cards = a.cardIds() == null ? null : List.copyOf(a.cardIds());
        return Arrays.asList(c.expectedVersion(), a.kind(), cards, a.interpretationKey());
    }

    private static void requireVersion(GameState state, long expectedVersion) {
        if (expectedVersion != state.version()) {
            throw new GameRuleException("VERSION_CONFLICT", "expected_version 与当前版本不符",
                    Map.of("expected_version", expectedVersion, "current_version", state.version()));
        }
    }

    /**
     * 仅校验会话（不取锁、不返回记录），供前置鉴权拦截器在请求体解析前调用；失败抛
     * {@link GameRuleException}（SESSION_INVALID/GAME_NOT_FOUND/SESSION_FORBIDDEN）。
     */
    public void requireSession(String gameId, String sessionToken) {
        authorize(gameId, sessionToken);
    }

    private GameRecord authorize(String gameId, String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new GameRuleException("SESSION_INVALID", "缺少会话令牌");
        }
        GameRecord rec = store.get(gameId);
        if (rec == null) {
            throw new GameRuleException("GAME_NOT_FOUND", "游戏不存在或已随服务重启清空",
                    Map.of("game_id", String.valueOf(gameId)));
        }
        if (rec.sessionToken.equals(sessionToken)) {
            return rec;
        }
        if (store.gameIdForToken(sessionToken) != null) {
            throw new GameRuleException("SESSION_FORBIDDEN", "令牌属于另一局", Map.of("game_id", rec.gameId));
        }
        throw new GameRuleException("SESSION_INVALID", "无效会话令牌");
    }
}
