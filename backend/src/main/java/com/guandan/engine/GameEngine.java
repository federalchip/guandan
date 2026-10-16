package com.guandan.engine;

import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.ActionKind;
import com.guandan.domain.model.DomainEvent;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.EventType;
import com.guandan.domain.model.GameResult;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.Outcome;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.Trick;
import com.guandan.domain.model.WildBinding;
import com.guandan.rules.LegalMoveGenerator;
import com.guandan.rules.MoveClassifier;
import com.guandan.rules.MoveComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纯牌局状态机：发牌、单动作转移、接风与结算。只在引擎内做纯转移，不修改传入状态，
 * 不依赖 Spring/Jackson/HTTP。消费 T2-T4 的 {@link Deck}、{@link MoveClassifier}、
 * {@link MoveComparator}、{@link LegalMoveGenerator}。
 *
 * <p>回合模型：{@code trick==null} 表示当前玩家领出（不能过牌）；出牌后 {@code trick.ownerId}
 * 为当前有效出牌者，{@code pendingResponders} 为仍需响应的活跃玩家。有人压过则桌面易主、待响应
 * 集合重置为除新主外全部活跃玩家（曾过牌者重新可响应）；待响应集合清空则一轮结束，由桌面拥有者
 * 领出，若其已出完则由仍持牌的搭档接风。出完触发结算：前二名同队即双下立即结束，否则第三名出完
 * 结束、余者自动末游并保留手牌。
 */
public final class GameEngine {

    private static final int SEATS = 4;

    private GameEngine() {
    }

    public static GameState newGame(long seed, RuleProfile profile) {
        List<List<Integer>> hands = Deck.deal(seed);
        return new GameState(profile, hands, List.of(), profile.firstPlayer(), null,
                List.of(), Phase.PLAYING, null, 0L);
    }

    /** 解析玩家实际选中的实体牌，返回当前局面下全部有效声明（跟牌只返回能压住桌面者）。 */
    public static List<Move> resolveSelection(GameState state, int playerId, List<Integer> cardIds) {
        requirePlaying(state);
        requireCurrentPlayer(state, playerId);
        return declarationsFor(state, playerId, cardIds);
    }

    /** 当前玩家的全部合法动作：生成器的 PLAY 候选，外加跟牌时允许的 PASS；顺序稳定。 */
    public static List<Action> getLegalActions(GameState state, int playerId) {
        requirePlaying(state);
        requireCurrentPlayer(state, playerId);
        List<Integer> hand = state.hands().get(playerId);
        Move previous = state.trick() == null ? null : state.trick().move();
        List<Action> actions = new ArrayList<>();
        for (Move m : LegalMoveGenerator.generateLegalMoves(hand, previous, state.profile())) {
            actions.add(Action.play(m.cardIds(), m.interpretationKey()));
        }
        if (state.trick() != null) {
            actions.add(Action.pass());
        }
        return List.copyOf(actions);
    }

    /** 对单个合法 PLAY/PASS 做纯转移，version+1；无效动作抛 {@link GameRuleException} 且不改动输入。 */
    public static EngineOutcome applyAction(GameState state, int playerId, Action action) {
        requirePlaying(state);
        requireWellFormedAction(action);
        requireCurrentPlayer(state, playerId);
        List<DomainEvent> events = new ArrayList<>();
        return action.kind() == ActionKind.PASS
                ? applyPass(state, playerId, events)
                : applyPlay(state, playerId, action, events);
    }

    // ---- 转移 ----

    private static EngineOutcome applyPlay(GameState state, int playerId, Action action,
                                           List<DomainEvent> events) {
        RuleProfile profile = state.profile();
        List<Move> decls = declarationsFor(state, playerId, action.cardIds());
        if (decls.isEmpty()) {
            throw new GameRuleException("INVALID_MOVE", "所选牌无有效牌型或压不过桌面",
                    Map.of("player_id", playerId));
        }
        Move chosen = chooseDeclaration(decls, action.interpretationKey());

        List<List<Integer>> hands = copyHands(state.hands());
        Set<Integer> played = new HashSet<>(chosen.cardIds());
        List<Integer> newHand = hands.get(playerId).stream().filter(id -> !played.contains(id)).toList();
        hands.set(playerId, newHand);

        List<Integer> discarded = new ArrayList<>(state.discarded());
        discarded.addAll(chosen.cardIds());

        List<Integer> finishOrder = new ArrayList<>(state.finishOrder());
        boolean finished = newHand.isEmpty();
        if (finished) {
            finishOrder.add(playerId);
        }

        Set<Integer> pending = new LinkedHashSet<>(activePlayers(hands));
        pending.remove(playerId);
        Trick trick = new Trick(playerId, chosen, pending);
        long version = state.version() + 1;

        events.add(playedEvent(playerId, chosen, newHand.size()));
        if (finished) {
            events.add(new DomainEvent(EventType.PLAYER_FINISHED, playerId,
                    Map.of("position", finishOrder.size())));
        }

        Phase phase = Phase.PLAYING;
        GameResult result = null;
        Integer currentPlayer;
        if (shouldSettle(finishOrder)) {
            result = settle(finishOrder);
            phase = Phase.FINISHED;
            currentPlayer = null;
            events.add(gameFinishedEvent(result));
        } else if (pending.isEmpty()) {
            trick = null;
            currentPlayer = leaderAfterClose(profile, playerId, hands);
            events.add(trickClosedEvent(currentPlayer));
        } else {
            currentPlayer = nextInPending(profile, playerId, pending);
        }

        GameState next = new GameState(profile, hands, discarded, currentPlayer, trick,
                finishOrder, phase, result, version);
        return new EngineOutcome(next, events);
    }

    private static EngineOutcome applyPass(GameState state, int playerId, List<DomainEvent> events) {
        if (state.trick() == null) {
            throw new GameRuleException("PASS_NOT_ALLOWED", "领出时不能过牌",
                    Map.of("player_id", playerId));
        }
        RuleProfile profile = state.profile();
        Set<Integer> pending = new LinkedHashSet<>(state.trick().pendingResponders());
        pending.remove(playerId);
        long version = state.version() + 1;
        events.add(new DomainEvent(EventType.PASSED, playerId, Map.of()));

        Trick trick;
        Integer currentPlayer;
        if (pending.isEmpty()) {
            int owner = state.trick().ownerId();
            trick = null;
            currentPlayer = leaderAfterClose(profile, owner, state.hands());
            events.add(trickClosedEvent(currentPlayer));
        } else {
            trick = new Trick(state.trick().ownerId(), state.trick().move(), pending);
            currentPlayer = nextInPending(profile, playerId, pending);
        }
        GameState next = new GameState(profile, state.hands(), state.discarded(), currentPlayer,
                trick, state.finishOrder(), Phase.PLAYING, null, version);
        return new EngineOutcome(next, events);
    }

    private static Move chooseDeclaration(List<Move> decls, String interpretationKey) {
        if (interpretationKey != null) {
            return decls.stream()
                    .filter(d -> interpretationKey.equals(d.interpretationKey()))
                    .findFirst()
                    .orElseThrow(() -> new GameRuleException("INVALID_DECLARATION",
                            "声明键与所选牌不匹配: " + interpretationKey,
                            Map.of("interpretation_key", interpretationKey)));
        }
        if (decls.size() > 1) {
            throw new GameRuleException("AMBIGUOUS_DECLARATION",
                    "所选牌有多个声明，需指定 interpretation_key",
                    Map.of("choices", List.copyOf(decls)));
        }
        return decls.get(0);
    }

    /** 校验并解析选牌：越界/重复由 {@link MoveClassifier} 抛错，再查归属，最后按桌面过滤。 */
    private static List<Move> declarationsFor(GameState state, int playerId, List<Integer> cardIds) {
        List<Integer> ids = cardIds == null ? List.of() : cardIds;
        RuleProfile profile = state.profile();
        List<Move> decls = MoveClassifier.classify(ids, profile);
        Set<Integer> hand = new HashSet<>(state.hands().get(playerId));
        for (int id : ids) {
            if (!hand.contains(id)) {
                throw new GameRuleException("CARD_NOT_OWNED", "card_id 不属于该玩家: " + id,
                        Map.of("card_id", id, "player_id", playerId));
            }
        }
        if (state.trick() != null) {
            Move prev = state.trick().move();
            decls = decls.stream().filter(d -> MoveComparator.canBeat(d, prev, profile)).toList();
        }
        return decls;
    }

    // ---- 回合/结算辅助 ----

    private static void requirePlaying(GameState state) {
        if (state.phase() == Phase.FINISHED) {
            throw new GameRuleException("GAME_FINISHED", "牌局已结束", Map.of());
        }
    }

    /**
     * 动作结构校验：kind 必填；PASS 不得携带牌或声明键（合同为 {@code {kind:"PASS"}}），
     * PLAY 必须携带至少一张牌。结构不合法统一抛 SCHEMA_INVALID（主计划映射 422），
     * 避免 null kind 落入 PLAY 分支或带牌 PASS 被当成合法过牌执行。
     */
    private static void requireWellFormedAction(Action action) {
        if (action == null || action.kind() == null) {
            throw new GameRuleException("SCHEMA_INVALID", "动作缺少 kind", Map.of());
        }
        if (action.kind() == ActionKind.PASS) {
            if (!action.cardIds().isEmpty() || action.interpretationKey() != null) {
                throw new GameRuleException("SCHEMA_INVALID", "PASS 不得携带牌或声明键",
                        Map.of("kind", ActionKind.PASS.name()));
            }
        } else if (action.cardIds().isEmpty()) {
            throw new GameRuleException("SCHEMA_INVALID", "PLAY 必须携带至少一张牌",
                    Map.of("kind", ActionKind.PLAY.name()));
        }
    }

    private static void requireCurrentPlayer(GameState state, int playerId) {
        Integer cp = state.currentPlayer();
        if (cp == null || cp.intValue() != playerId) {
            throw new GameRuleException("NOT_YOUR_TURN", "非当前玩家: " + playerId,
                    Map.of("player_id", playerId));
        }
    }

    private static Set<Integer> activePlayers(List<List<Integer>> hands) {
        Set<Integer> active = new LinkedHashSet<>();
        for (int p = 0; p < hands.size(); p++) {
            if (!hands.get(p).isEmpty()) {
                active.add(p);
            }
        }
        return active;
    }

    private static Integer nextInPending(RuleProfile profile, int after, Set<Integer> pending) {
        return scanFrom(profile, after, pending::contains);
    }

    private static Integer nextActive(RuleProfile profile, int after, List<List<Integer>> hands) {
        return scanFrom(profile, after, p -> p < hands.size() && !hands.get(p).isEmpty());
    }

    private static Integer scanFrom(RuleProfile profile, int after, java.util.function.IntPredicate match) {
        List<Integer> order = profile.turnOrder();
        int n = order.size();
        int start = order.indexOf(after);
        if (start < 0) {
            start = 0;
        }
        for (int i = 1; i <= n; i++) {
            int p = order.get((start + i) % n);
            if (match.test(p)) {
                return p;
            }
        }
        return null;
    }

    /** 一轮结束后的领出者：桌面拥有者仍持牌则其领出，否则由仍持牌的搭档接风。 */
    private static Integer leaderAfterClose(RuleProfile profile, int owner, List<List<Integer>> hands) {
        if (owner < hands.size() && !hands.get(owner).isEmpty()) {
            return owner;
        }
        int partner = partner(owner);
        if (partner < hands.size() && !hands.get(partner).isEmpty()) {
            return partner;
        }
        return nextActive(profile, owner, hands);
    }

    private static boolean shouldSettle(List<Integer> finishOrder) {
        int n = finishOrder.size();
        if (n >= 2 && teamOf(finishOrder.get(0)) == teamOf(finishOrder.get(1))) {
            return true;
        }
        return n >= 3;
    }

    private static GameResult settle(List<Integer> finishOrder) {
        int first = finishOrder.get(0);
        int winnerTeam = teamOf(first);
        List<Integer> rest = nonFinishers(finishOrder);
        List<List<Integer>> groups = new ArrayList<>();
        Outcome outcome;
        if (finishOrder.size() == 2 && teamOf(finishOrder.get(0)) == teamOf(finishOrder.get(1))) {
            groups.add(List.of(finishOrder.get(0)));
            groups.add(List.of(finishOrder.get(1)));
            groups.add(List.copyOf(rest));
            outcome = Outcome.DOUBLE_UP;
        } else {
            for (int p : finishOrder) {
                groups.add(List.of(p));
            }
            groups.add(List.of(rest.get(0)));
            outcome = finishOrder.get(2).intValue() == partner(first)
                    ? Outcome.TEAMMATE_THIRD
                    : Outcome.TEAMMATE_LAST;
        }
        return new GameResult(winnerTeam, groups, outcome);
    }

    private static List<Integer> nonFinishers(List<Integer> finishOrder) {
        List<Integer> out = new ArrayList<>();
        for (int p = 0; p < SEATS; p++) {
            if (!finishOrder.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static int teamOf(int player) {
        return player % 2;
    }

    private static int partner(int player) {
        return (player + 2) % SEATS;
    }

    private static List<List<Integer>> copyHands(List<List<Integer>> src) {
        List<List<Integer>> out = new ArrayList<>(src.size());
        for (List<Integer> h : src) {
            out.add(new ArrayList<>(h));
        }
        return out;
    }

    // ---- 事件 ----

    private static DomainEvent playedEvent(int playerId, Move move, int remaining) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cards", List.copyOf(move.cardIds()));
        payload.put("kind", move.kind().name());
        payload.put("main_rank", move.mainRank());
        payload.put("length", move.length());
        if (move.interpretationKey() != null) {
            payload.put("interpretation_key", move.interpretationKey());
        }
        payload.put("wild_bindings", wildBindingsPayload(move));
        payload.put("remaining", remaining);
        return new DomainEvent(EventType.PLAYED, playerId, payload);
    }

    /** 百搭绑定公开载荷：{card_id, rank, suit}；仅同花顺 suit 为所声明花色，其余为 null。深度冻结（保留 suit:null）。 */
    private static List<Map<String, Object>> wildBindingsPayload(Move move) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WildBinding wb : move.wildBindings()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("card_id", wb.cardId());
            b.put("rank", wb.rank());
            b.put("suit", wb.suit() == null ? null : wb.suit().name());
            out.add(Collections.unmodifiableMap(b));
        }
        return List.copyOf(out);
    }

    private static DomainEvent trickClosedEvent(Integer nextLeader) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("next_leader", nextLeader == null ? -1 : nextLeader);
        return new DomainEvent(EventType.TRICK_CLOSED, null, payload);
    }

    private static DomainEvent gameFinishedEvent(GameResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("winner_team", result.winnerTeam());
        payload.put("outcome", result.outcome().name());
        payload.put("placement_groups", result.placementGroups());
        return new DomainEvent(EventType.GAME_FINISHED, null, payload);
    }
}
