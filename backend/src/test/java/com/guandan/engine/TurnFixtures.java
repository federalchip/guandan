package com.guandan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.Trick;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试辅助：加载人工 fixture turn_cases.json，由 setup 构造 {@link GameState}，把 steps 转为
 * {@link Action}，并提供守恒断言。discarded 取四家手牌在 108 张中的补集（含所有已打出实体牌），
 * 当前 trick 的牌属于 discarded、不额外计数。expected 一律来自人工推导，不由引擎反填。
 */
final class TurnFixtures {

    static final RuleProfile PROFILE = RuleProfile.v1();
    private static final JsonNode ROOT = load();

    private TurnFixtures() {
    }

    private static JsonNode load() {
        try (InputStream in = TurnFixtures.class.getResourceAsStream("/fixtures/turn_cases.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: /fixtures/turn_cases.json");
            }
            return new ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static List<String> caseIds() {
        List<String> ids = new ArrayList<>();
        for (JsonNode s : ROOT.get("scenarios")) {
            ids.add(s.get("case_id").asText());
        }
        return ids;
    }

    static JsonNode scenario(String caseId) {
        for (JsonNode s : ROOT.get("scenarios")) {
            if (s.get("case_id").asText().equals(caseId)) {
                return s;
            }
        }
        throw new IllegalArgumentException("no scenario: " + caseId);
    }

    static boolean isReject(JsonNode sc) {
        return sc.has("expected") && sc.get("expected").has("rejected")
                && sc.get("expected").get("rejected").asBoolean();
    }

    /** 由 setup 构造初始状态；hands 用符号解析，discarded 取补集，trick 在 v1 fixture 中均为 null。 */
    static GameState initialState(JsonNode sc) {
        JsonNode setup = sc.get("setup");
        List<List<Integer>> hands = new ArrayList<>(4);
        Set<Integer> union = new HashSet<>();
        for (int p = 0; p < 4; p++) {
            List<Integer> h = parseCards(setup.get("hands").get(String.valueOf(p)));
            hands.add(h);
            union.addAll(h);
        }
        List<Integer> discarded = new ArrayList<>();
        for (int id = 0; id < Deck.TOTAL_CARDS; id++) {
            if (!union.contains(id)) {
                discarded.add(id);
            }
        }
        JsonNode cpNode = setup.get("current_player");
        Integer currentPlayer = cpNode == null || cpNode.isNull() ? null : cpNode.asInt();
        Trick trick = null;
        if (setup.has("trick") && !setup.get("trick").isNull()) {
            throw new IllegalStateException("v1 fixture 不支持非空 trick setup: " + sc.get("case_id").asText());
        }
        List<Integer> finishOrder = parseInts(setup.get("finish_order"));
        Phase phase = Phase.valueOf(setup.get("phase").asText());
        long version = setup.get("version").asLong();
        return new GameState(PROFILE, hands, discarded, currentPlayer, trick, finishOrder, phase, null, version);
    }

    record Step(int actor, String kind, Action action) {
    }

    static List<Step> steps(JsonNode sc) {
        List<Step> out = new ArrayList<>();
        for (JsonNode st : sc.get("steps")) {
            int actor = st.get("actor").asInt();
            String kind = st.get("kind").asText();
            Action action = kind.equals("PASS")
                    ? Action.pass()
                    : Action.play(parseCards(st.get("cards")), null);
            out.add(new Step(actor, kind, action));
        }
        return out;
    }

    static List<Integer> parseCards(JsonNode arr) {
        List<Integer> ids = new ArrayList<>();
        if (arr == null) {
            return ids;
        }
        for (JsonNode n : arr) {
            ids.add(CardIds.parseOne(n.asText()));
        }
        return ids;
    }

    static List<Integer> parseInts(JsonNode arr) {
        List<Integer> ids = new ArrayList<>();
        if (arr == null) {
            return ids;
        }
        for (JsonNode n : arr) {
            ids.add(n.asInt());
        }
        return ids;
    }

    /** I1：四家手牌与 discarded 互不重叠、合计 108 个不同 CardId；当前 trick 牌均属于 discarded。 */
    static void assertConservation(GameState s) {
        Set<Integer> all = new HashSet<>();
        for (List<Integer> h : s.hands()) {
            for (int id : h) {
                assertTrue(all.add(id), "手牌内部/之间不重叠: " + id);
            }
        }
        for (int id : s.discarded()) {
            assertTrue(all.add(id), "手牌与 discarded 不重叠: " + id);
        }
        assertEquals(Deck.TOTAL_CARDS, all.size(), "四家手牌 + discarded 合计 108 个不同 CardId");
        if (s.trick() != null) {
            for (int id : s.trick().move().cardIds()) {
                assertTrue(s.discarded().contains(id), "trick 牌属于 discarded: " + id);
            }
        }
    }

    /** I3：已出完玩家不再成为 current_player。 */
    static void assertFinishedNeverCurrent(GameState s) {
        if (s.currentPlayer() != null) {
            assertTrue(!s.finishOrder().contains(s.currentPlayer()),
                    "已出完玩家 " + s.currentPlayer() + " 不应为当前玩家");
        }
    }
}
