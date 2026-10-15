package com.guandan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.ActionKind;
import com.guandan.domain.model.DomainEvent;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.EventType;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import com.guandan.domain.model.Outcome;
import com.guandan.domain.model.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 引擎状态机测试。命名测试覆盖计划要求的各项行为；参数化测试逐条重放人工 fixture
 * turn_cases.json（S01-S14），断言由 docs/rules-v1.md 第6-7节人工推导的 expected，不由引擎反填。
 */
class GameEngineTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();

    // ---- 计划要求的命名测试 ----

    @Test
    void test_lead_cannot_pass() {
        GameState s = TurnFixtures.initialState(TurnFixtures.scenario("S01"));
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(s, 0, Action.pass()));
        assertEquals("PASS_NOT_ALLOWED", ex.code());
        assertEquals(1L, s.version(), "被拒后 version 不变");
    }

    @Test
    void test_invalid_action_does_not_mutate_input() {
        GameState s = TurnFixtures.initialState(TurnFixtures.scenario("S13a"));
        GameState pristine = TurnFixtures.initialState(TurnFixtures.scenario("S13a"));
        assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(s, 0, Action.play(CardIds.parse("S3@0 H5@0"), null)));
        assertEquals(pristine, s, "被拒动作后输入状态完全不变（record 相等）");
    }

    @Test
    void test_valid_action_is_pure_transition() {
        GameState s = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        GameState pristine = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        EngineOutcome out = GameEngine.applyAction(s, 0, Action.play(CardIds.parse("S3@0"), null));
        assertEquals(pristine, s, "合法动作不修改输入状态");
        assertNotSame(s, out.state(), "返回新状态对象");
        assertEquals(s.version() + 1, out.state().version(), "合法动作 version+1");
    }

    @Test
    void test_turn_skips_finished_player() {
        GameState s = replay("S07");
        assertEquals(2, s.currentPlayer(), "跳过已出完的 1，下一家为 2");
        assertEquals(0, s.trick().ownerId());
    }

    @Test
    void test_passed_player_can_respond_to_new_play() {
        GameState s = replay("S08");
        assertEquals(2, s.trick().ownerId());
        assertTrue(s.trick().pendingResponders().contains(1), "过牌玩家 1 重新进入待响应集合");
        assertEquals(Set.of(0, 1, 3), s.trick().pendingResponders());
        assertEquals(3, s.currentPlayer());
    }

    @Test
    void test_responder_set_uses_active_players() {
        GameState s = replay("S07");
        assertEquals(Set.of(2, 3), s.trick().pendingResponders(), "待响应集合排除已出完的 1");
    }

    @Test
    void test_last_play_unbeaten_gives_partner_lead() {
        GameState s = replay("S05");
        assertNull(s.trick(), "一轮结束清空桌面");
        assertEquals(2, s.currentPlayer(), "未被压住，搭档 2 接风领出");
        assertEquals(List.of(0), s.finishOrder());
    }

    @Test
    void test_last_play_beaten_does_not_give_partner_lead() {
        GameState s = replay("S06");
        assertNull(s.trick());
        assertEquals(1, s.currentPlayer(), "被 1 压住，由 1 领出，不给搭档 2 接风");
    }

    @Test
    void test_double_up_has_shared_last_group() {
        GameState s = replay("S09");
        assertEquals(Phase.FINISHED, s.phase());
        assertNull(s.currentPlayer());
        assertEquals(Outcome.DOUBLE_UP, s.result().outcome());
        assertEquals(List.of(List.of(0), List.of(2), List.of(1, 3)), s.result().placementGroups());
        assertEquals(0, s.result().winnerTeam());
    }

    @Test
    void test_third_finish_assigns_remaining_last() {
        GameState s = replay("S10");
        assertEquals(Phase.FINISHED, s.phase());
        assertEquals(Outcome.TEAMMATE_THIRD, s.result().outcome());
        assertEquals(List.of(List.of(0), List.of(1), List.of(2), List.of(3)), s.result().placementGroups());
        assertEquals(CardIds.parse("C8@0 C9@0 C10@0"), s.hands().get(3), "末游手牌保留");
    }

    @Test
    void test_terminal_remaining_cards_still_conserved() {
        for (String id : List.of("S10", "S11", "S14")) {
            GameState s = replay(id);
            TurnFixtures.assertConservation(s);
        }
    }

    @Test
    void test_new_game_deals_108_and_conserves() {
        GameState s = GameEngine.newGame(1234L, PROFILE);
        assertEquals(Phase.PLAYING, s.phase());
        assertEquals(0, s.currentPlayer());
        assertNull(s.trick());
        assertTrue(s.discarded().isEmpty());
        assertEquals(4, s.hands().size());
        for (List<Integer> h : s.hands()) {
            assertEquals(Deck.HAND_SIZE, h.size());
        }
        TurnFixtures.assertConservation(s);
        // 同 seed 可复现
        assertEquals(GameEngine.newGame(1234L, PROFILE).hands(), s.hands());
    }

    @Test
    void test_apply_on_finished_is_rejected() {
        GameState finished = replay("S09");
        assertEquals(Phase.FINISHED, finished.phase());
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(finished, 1, Action.pass()));
        assertEquals("GAME_FINISHED", ex.code());
        assertEquals(finished.version(), replay("S09").version());
    }

    @Test
    void test_get_legal_actions_includes_pass_only_when_following() {
        GameState lead = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        List<Action> leadActions = GameEngine.getLegalActions(lead, 0);
        assertTrue(leadActions.stream().allMatch(a -> a.kind() == com.guandan.domain.model.ActionKind.PLAY),
                "领出无 PASS");
        assertTrue(leadActions.stream().anyMatch(a -> a.cardIds().size() == 1), "领出至少一个单张");

        GameState following = GameEngine.applyAction(lead, 0, Action.play(CardIds.parse("S3@0"), null)).state();
        List<Action> followActions = GameEngine.getLegalActions(following, 1);
        assertTrue(followActions.stream().anyMatch(a -> a.kind() == com.guandan.domain.model.ActionKind.PASS),
                "跟牌允许 PASS");
    }

    @Test
    void test_resolve_selection_returns_beating_declarations_only() {
        GameState lead = TurnFixtures.initialState(TurnFixtures.scenario("S08"));
        // 0 领出 S3 后，1 手里 S5 可压；用 resolveSelection 解析 1 的 S5
        GameState afterLead = GameEngine.applyAction(lead, 0, Action.play(CardIds.parse("S3@0"), null)).state();
        List<com.guandan.domain.model.Move> decls =
                GameEngine.resolveSelection(afterLead, 1, CardIds.parse("S5@0"));
        assertEquals(1, decls.size());
        assertEquals(com.guandan.domain.model.MoveKind.SINGLE, decls.get(0).kind());
        // 选择不属于自己手牌 → CARD_NOT_OWNED
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.resolveSelection(afterLead, 1, CardIds.parse("SA@0")));
        assertEquals("CARD_NOT_OWNED", ex.code());
    }

    // ---- 动作结构校验（SCHEMA_INVALID）----

    @Test
    void test_pass_carrying_cards_is_rejected() {
        GameState following = GameEngine.applyAction(
                TurnFixtures.initialState(TurnFixtures.scenario("S04")), 0,
                Action.play(CardIds.parse("S3@0"), null)).state();
        long vBefore = following.version();
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(following, 1,
                        new Action(ActionKind.PASS, CardIds.parse("S5@0"), null)));
        assertEquals("SCHEMA_INVALID", ex.code(), "带牌 PASS 结构非法");
        assertEquals(vBefore, following.version(), "被拒后 version 不变");
        // 对照：同局面合法 PASS 被接受，证明拒绝源于动作结构而非局面
        assertEquals(vBefore + 1, GameEngine.applyAction(following, 1, Action.pass()).state().version());
    }

    @Test
    void test_pass_carrying_interpretation_key_is_rejected() {
        GameState following = GameEngine.applyAction(
                TurnFixtures.initialState(TurnFixtures.scenario("S04")), 0,
                Action.play(CardIds.parse("S3@0"), null)).state();
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(following, 1,
                        new Action(ActionKind.PASS, List.of(), "gd-v1-level2|SINGLE|1|3|8")));
        assertEquals("SCHEMA_INVALID", ex.code(), "带声明键 PASS 结构非法");
    }

    @Test
    void test_null_kind_action_is_rejected() {
        GameState s = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(s, 0, new Action(null, CardIds.parse("S3@0"), null)));
        assertEquals("SCHEMA_INVALID", ex.code(), "kind=null 不得落入 PLAY 分支");
        assertEquals(1L, s.version());
    }

    @Test
    void test_play_without_cards_is_rejected() {
        GameState s = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(s, 0, Action.play(List.of(), null)));
        assertEquals("SCHEMA_INVALID", ex.code(), "PLAY 必须携带牌");
        assertEquals(1L, s.version());
    }

    @Test
    void test_null_action_is_rejected() {
        GameState s = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> GameEngine.applyAction(s, 0, null));
        assertEquals("SCHEMA_INVALID", ex.code());
    }

    // ---- PLAYED 事件携带完整公开声明 ----

    @Test
    void test_played_event_carries_wild_binding_without_suit() {
        // 0 手持 S3@0 + 百搭 H2@0，领出组成对3：百搭充当 3，非顺子 suit 为 null
        List<Integer> p0 = CardIds.parse("S3@0 H2@0");
        GameState s = stateWithHands(List.of(
                p0, CardIds.parse("S5@0"), CardIds.parse("S6@0"), CardIds.parse("S7@0")));
        EngineOutcome out = GameEngine.applyAction(s, 0, Action.play(p0, null));
        Map<String, Object> p = playedPayload(out);
        assertEquals("PAIR", p.get("kind"));
        assertEquals(3, ((Number) p.get("main_rank")).intValue());
        assertEquals(2, ((Number) p.get("length")).intValue());
        assertEquals(0, ((Number) p.get("remaining")).intValue());
        assertNotNull(p.get("interpretation_key"));
        @SuppressWarnings("unchecked")
        List<Integer> cards = (List<Integer>) p.get("cards");
        assertEquals(2, cards.size());
        assertTrue(cards.containsAll(List.of(1, 4)), "cards 含 H2@0(1) 与 S3@0(4)");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) p.get("wild_bindings");
        assertEquals(1, bindings.size());
        assertEquals(1, ((Number) bindings.get(0).get("card_id")).intValue(), "百搭实体 H2@0=1");
        assertEquals(3, ((Number) bindings.get(0).get("rank")).intValue());
        assertNull(bindings.get(0).get("suit"), "非顺子 suit 为 null");
    }

    @Test
    void test_played_event_straight_flush_binding_has_suit() {
        // S3-S6 + 百搭 → 同花顺，百搭充当黑桃；事件 suit 应为所声明花色 "S"
        List<Integer> p0 = CardIds.parse("S3@0 S4@0 S5@0 S6@0 H2@0");
        GameState s = stateWithHands(List.of(
                p0, CardIds.parse("S8@0"), CardIds.parse("S9@0"), CardIds.parse("S10@0")));
        Move sf = GameEngine.resolveSelection(s, 0, p0).stream()
                .filter(d -> d.kind() == MoveKind.STRAIGHT_FLUSH)
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在同花顺声明"));
        EngineOutcome out = GameEngine.applyAction(s, 0, Action.play(p0, sf.interpretationKey()));
        Map<String, Object> p = playedPayload(out);
        assertEquals("STRAIGHT_FLUSH", p.get("kind"));
        assertEquals(sf.mainRank(), ((Number) p.get("main_rank")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) p.get("wild_bindings");
        assertEquals(1, bindings.size());
        assertEquals(1, ((Number) bindings.get(0).get("card_id")).intValue());
        assertEquals("S", bindings.get(0).get("suit"), "同花顺 suit 为所声明花色");
    }

    private static Map<String, Object> playedPayload(EngineOutcome out) {
        DomainEvent played = out.events().stream()
                .filter(e -> e.eventType() == EventType.PLAYED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("应有 PLAYED 事件"));
        assertEquals(0, played.actorId());
        return played.payload();
    }

    private static GameState stateWithHands(List<List<Integer>> hands) {
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
        return new GameState(PROFILE, hands, discarded, 0, null, List.of(), Phase.PLAYING, null, 1L);
    }

    // ---- 人工 fixture 全量重放 ----

    static List<String> allCaseIds() {
        return TurnFixtures.caseIds();
    }

    @ParameterizedTest
    @MethodSource("allCaseIds")
    void test_turn_scenario(String caseId) {
        JsonNode sc = TurnFixtures.scenario(caseId);
        GameState initial = TurnFixtures.initialState(sc);
        TurnFixtures.assertConservation(initial);
        List<TurnFixtures.Step> steps = TurnFixtures.steps(sc);

        if (TurnFixtures.isReject(sc)) {
            JsonNode exp = sc.get("expected");
            TurnFixtures.Step step = steps.get(0);
            GameRuleException ex = assertThrows(GameRuleException.class,
                    () -> GameEngine.applyAction(initial, step.actor(), step.action()),
                    caseId + " 应被拒绝");
            assertEquals(exp.get("error").asText(), ex.code(), caseId + " 错误码");
            assertEquals(exp.get("version").asLong(), initial.version(), caseId + " version 不变");
            assertEquals(TurnFixtures.initialState(sc), initial, caseId + " 输入状态不变");
            if (exp.has("current_player") && !exp.get("current_player").isNull()) {
                assertEquals(exp.get("current_player").asInt(), initial.currentPlayer(), caseId + " cp 不变");
            }
            if (exp.has("phase")) {
                assertEquals(Phase.valueOf(exp.get("phase").asText()), initial.phase(), caseId + " phase 不变");
            }
            if (exp.has("returns_choices") && exp.get("returns_choices").asBoolean()) {
                Object choices = ex.details().get("choices");
                assertNotNull(choices, caseId + " 返回 choices");
                assertTrue(((List<?>) choices).size() > 1, caseId + " 多声明 choices");
            }
            return;
        }

        GameState cur = initial;
        for (TurnFixtures.Step step : steps) {
            EngineOutcome out = GameEngine.applyAction(cur, step.actor(), step.action());
            cur = out.state();
            TurnFixtures.assertConservation(cur);
            TurnFixtures.assertFinishedNeverCurrent(cur);
        }
        assertExpectedFinal(caseId, cur, sc.get("expected_final"));
    }

    private static void assertExpectedFinal(String caseId, GameState s, JsonNode exp) {
        if (exp.has("phase")) {
            assertEquals(Phase.valueOf(exp.get("phase").asText()), s.phase(), caseId + " phase");
        }
        if (exp.has("version")) {
            assertEquals(exp.get("version").asLong(), s.version(), caseId + " version");
        }
        if (exp.has("current_player")) {
            if (exp.get("current_player").isNull()) {
                assertNull(s.currentPlayer(), caseId + " current_player 应为 null");
            } else {
                assertEquals(exp.get("current_player").asInt(), s.currentPlayer(), caseId + " current_player");
            }
        }
        if (exp.has("is_lead")) {
            assertEquals(exp.get("is_lead").asBoolean(), s.trick() == null, caseId + " is_lead");
        }
        if (exp.has("trick_owner")) {
            if (exp.get("trick_owner").isNull()) {
                assertNull(s.trick(), caseId + " trick 应为 null");
            } else {
                assertNotNull(s.trick(), caseId + " trick 非空");
                assertEquals(exp.get("trick_owner").asInt(), s.trick().ownerId(), caseId + " trick_owner");
            }
        }
        if (exp.has("pending_responders")) {
            Set<Integer> expected = new HashSet<>(TurnFixtures.parseInts(exp.get("pending_responders")));
            Set<Integer> actual = s.trick() == null ? Set.of() : s.trick().pendingResponders();
            assertEquals(expected, actual, caseId + " pending_responders");
        }
        if (exp.has("finish_order")) {
            assertEquals(TurnFixtures.parseInts(exp.get("finish_order")), s.finishOrder(), caseId + " finish_order");
        }
        if (exp.has("remaining_hands")) {
            JsonNode rh = exp.get("remaining_hands");
            for (String k : arrayList(rh.fieldNames())) {
                int seat = Integer.parseInt(k);
                assertEquals(TurnFixtures.parseCards(rh.get(k)), s.hands().get(seat),
                        caseId + " 座位 " + seat + " 剩余手牌");
            }
        }
        if (exp.has("result")) {
            JsonNode r = exp.get("result");
            assertNotNull(s.result(), caseId + " result 非空");
            assertEquals(r.get("winner_team").asInt(), s.result().winnerTeam(), caseId + " winner_team");
            assertEquals(r.get("outcome").asText(), s.result().outcome().name(), caseId + " outcome");
            List<List<Integer>> groups = new ArrayList<>();
            for (JsonNode g : r.get("placement_groups")) {
                groups.add(TurnFixtures.parseInts(g));
            }
            assertEquals(groups, s.result().placementGroups(), caseId + " placement_groups");
        }
    }

    private static List<String> arrayList(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    /** 重放某场景全部步骤并返回终态；每步校验守恒与“已出完不再当前”。 */
    private static GameState replay(String caseId) {
        JsonNode sc = TurnFixtures.scenario(caseId);
        GameState cur = TurnFixtures.initialState(sc);
        for (TurnFixtures.Step step : TurnFixtures.steps(sc)) {
            cur = GameEngine.applyAction(cur, step.actor(), step.action()).state();
            TurnFixtures.assertConservation(cur);
            TurnFixtures.assertFinishedNeverCurrent(cur);
        }
        assertFalse(cur.hands().isEmpty());
        return cur;
    }
}
