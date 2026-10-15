package com.guandan.service;

import com.guandan.api.dto.ActionRequest;
import com.guandan.api.dto.CommandRequest;
import com.guandan.api.dto.CommandResponse;
import com.guandan.api.dto.CreatedGame;
import com.guandan.api.dto.DtoMapper;
import com.guandan.api.dto.EventView;
import com.guandan.api.dto.GameView;
import com.guandan.api.dto.LegalActionsResponse;
import com.guandan.api.dto.ResolveResponse;
import com.guandan.bots.BotSeeds;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.DomainEvent;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.PlayerView;
import com.guandan.engine.GameEngine;
import com.guandan.engine.PlayerViewFactory;
import com.guandan.rules.LegalMoveGenerator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

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
    private static final int MAX_BOT_STEPS = 512;
    private static final int MIN_CARDS = 1;
    private static final int MAX_CARDS = 10;

    private final InMemoryGameStore store;
    private final GameSeedSource seedSource;
    private final BotPolicy policy;
    private final ActionAdvancer advancer;

    public GameService(InMemoryGameStore store, GameSeedSource seedSource, BotPolicy policy,
                       ActionAdvancer advancer) {
        this.store = store;
        this.seedSource = seedSource;
        this.policy = policy;
        this.advancer = advancer;
    }

    public CreatedGame createGame() {
        long seed = seedSource.nextSeed();
        GameState state = GameEngine.newGame(seed, PROFILE);
        String gameId = UUID.randomUUID().toString();
        String sessionToken = UUID.randomUUID().toString();
        store.create(gameId, sessionToken, seed, state);
        GameView view = DtoMapper.toGameView(gameId, PlayerViewFactory.getPlayerView(state, HUMAN_SEAT));
        return new CreatedGame(gameId, sessionToken, view);
    }

    public GameView getGame(String gameId, String sessionToken) {
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            return DtoMapper.toGameView(rec.gameId, PlayerViewFactory.getPlayerView(rec.state, HUMAN_SEAT));
        } finally {
            rec.lock.unlock();
        }
    }

    public ResolveResponse resolve(String gameId, String sessionToken, long expectedVersion,
                                   List<Integer> cardIds) {
        validateCardIds(cardIds);
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            GameState state = rec.state;
            requireVersion(state, expectedVersion);
            List<Move> choices = GameEngine.resolveSelection(state, HUMAN_SEAT, cardIds);
            return new ResolveResponse(state.version(), DtoMapper.toMoveViews(choices));
        } finally {
            rec.lock.unlock();
        }
    }

    public LegalActionsResponse legalActions(String gameId, String sessionToken) {
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
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
        command.validate();
        GameRecord rec = authorize(gameId, sessionToken);
        rec.lock.lock();
        try {
            String fingerprint = fingerprint(command);
            CachedCommand cached = rec.idempotency.get(command.requestId());
            if (cached != null) {
                if (cached.fingerprint().equals(fingerprint)) {
                    return cached.response();
                }
                throw new GameRuleException("REQUEST_ID_REUSED", "request_id 已用于不同请求体",
                        Map.of("request_id", command.requestId()));
            }

            GameState work = rec.state;
            requireVersion(work, command.expectedVersion());

            List<SequencedEvent> newEvents = new ArrayList<>();
            long seq = rec.nextSeq;

            EngineOutcome humanOut = advancer.applyAction(work, HUMAN_SEAT, toAction(command.action()));
            seq = appendEvents(newEvents, seq, humanOut);
            work = humanOut.state();
            long acceptedVersion = work.version();

            int botSteps = 0;
            while (work.phase() == Phase.PLAYING) {
                Integer cp = work.currentPlayer();
                if (cp == null) {
                    break;
                }
                if (cp == HUMAN_SEAT && !work.hands().get(HUMAN_SEAT).isEmpty()) {
                    break;
                }
                if (botSteps >= MAX_BOT_STEPS) {
                    throw new GameRuleException("BOT_STEP_LIMIT",
                            "机器人步数超过上限 " + MAX_BOT_STEPS, Map.of("limit", MAX_BOT_STEPS));
                }
                int seat = cp;
                List<Action> legal = GameEngine.getLegalActions(work, seat);
                PlayerView view = PlayerViewFactory.getPlayerView(work, seat);
                long botSeed = BotSeeds.derive(rec.gameSeed, work.version(), seat);
                Action botAction = policy.chooseAction(view, legal, new SplittableRandom(botSeed));
                EngineOutcome botOut = advancer.applyAction(work, seat, botAction);
                seq = appendEvents(newEvents, seq, botOut);
                work = botOut.state();
                botSteps++;
            }

            rec.state = work;
            rec.events.addAll(newEvents);
            rec.nextSeq = seq;
            GameView finalView = DtoMapper.toGameView(rec.gameId,
                    PlayerViewFactory.getPlayerView(work, HUMAN_SEAT));
            List<EventView> eventViews = new ArrayList<>(newEvents.size());
            for (SequencedEvent se : newEvents) {
                eventViews.add(DtoMapper.toEventView(se.seq(), se.stateVersion(), se.event()));
            }
            CommandResponse response = new CommandResponse(command.requestId(), acceptedVersion,
                    finalView, List.copyOf(eventViews));
            rec.idempotency.put(command.requestId(), new CachedCommand(fingerprint, response));
            return response;
        } finally {
            rec.lock.unlock();
        }
    }

    // ---- 辅助 ----

    private static long appendEvents(List<SequencedEvent> sink, long seq, EngineOutcome out) {
        long stateVersion = out.state().version();
        for (DomainEvent e : out.events()) {
            sink.add(new SequencedEvent(seq++, stateVersion, e));
        }
        return seq;
    }

    private static Action toAction(ActionRequest ar) {
        return "PASS".equals(ar.kind()) ? Action.pass() : Action.play(ar.cardIds(), ar.interpretationKey());
    }

    private static String fingerprint(CommandRequest c) {
        ActionRequest a = c.action();
        return c.expectedVersion() + "|" + a.kind() + "|" + (a.cardIds() == null ? "" : a.cardIds())
                + "|" + a.interpretationKey();
    }

    private static void requireVersion(GameState state, long expectedVersion) {
        if (expectedVersion != state.version()) {
            throw new GameRuleException("VERSION_CONFLICT", "expected_version 与当前版本不符",
                    Map.of("expected_version", expectedVersion, "current_version", state.version()));
        }
    }

    private static void validateCardIds(List<Integer> cardIds) {
        if (cardIds == null || cardIds.size() < MIN_CARDS || cardIds.size() > MAX_CARDS) {
            throw new GameRuleException("SCHEMA_INVALID",
                    "card_ids 需 " + MIN_CARDS + "-" + MAX_CARDS + " 张");
        }
        for (Integer id : cardIds) {
            if (id == null) {
                throw new GameRuleException("SCHEMA_INVALID", "card_ids 含 null");
            }
        }
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
