package com.guandan.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.guandan.domain.CardIds;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import com.guandan.domain.model.WildBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.params.provider.Arguments;

class MoveClassifierTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();

    static Stream<Arguments> declarationCaseIds() {
        return RuleFixtures.idsOfType("declaration").stream().map(Arguments::of);
    }

    static Stream<Arguments> errorCaseIds() {
        return RuleFixtures.idsOfType("error").stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declarationCaseIds")
    void test_classify_manual_cases(String caseId) {
        JsonNode c = RuleFixtures.BY_ID.get(caseId);
        List<Integer> ids = RuleFixtures.selectedIds(c);
        List<Move> actual = MoveClassifier.classify(ids, PROFILE);

        Set<String> expectedKeys = new TreeSet<>();
        for (JsonNode d : c.get("expected_declarations")) {
            expectedKeys.add(semanticKey(d.get("move_kind").asText(), d.get("length").asInt(), d.get("main_rank").asInt()));
        }
        Set<String> actualKeys = new TreeSet<>();
        for (Move m : actual) {
            actualKeys.add(semanticKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertEquals(expectedKeys, actualKeys,
                caseId + " 完整语义声明集合必须相等: " + c.path("note").asText());

        for (JsonNode exp : c.get("expected_declarations")) {
            String key = semanticKey(exp.get("move_kind").asText(), exp.get("length").asInt(), exp.get("main_rank").asInt());
            Move m = actual.stream()
                    .filter(x -> semanticKey(x.kind().name(), x.length(), x.mainRank()).equals(key))
                    .findFirst().orElse(null);
            assertNotNull(m, caseId + " 缺少声明 " + key);
            assertEquals(ids.size(), m.cardIds().size(), caseId + " " + key + " 应消耗全部所选牌");
            assertEquals(sortedCopy(ids), sortedCopy(m.cardIds()), caseId + " " + key + " cardIds");
            assertEquals(expectedBindings(exp.get("wild_bindings")), actualBindings(m.wildBindings()),
                    caseId + " " + key + " wild_bindings");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorCaseIds")
    void test_invalid_input_rejected(String caseId) {
        JsonNode c = RuleFixtures.BY_ID.get(caseId);
        List<Integer> ids = RuleFixtures.selectedIds(c);
        String expected = c.get("expected_error").asText();
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> MoveClassifier.classify(ids, PROFILE), caseId);
        assertEquals(expected, ex.code(), caseId + " 错误码");
    }

    @Test
    void test_low_ace_has_correct_high_rank() {
        List<Move> straight = MoveClassifier.classify(CardIds.parse("SA@0 S2@0 D3@0 C4@0 H5@0"), PROFILE);
        assertEquals(1, straight.size());
        assertEquals(MoveKind.STRAIGHT, straight.get(0).kind());
        assertEquals(5, straight.get(0).mainRank(), "A2345 高端点为 5");

        List<Move> pairRun = MoveClassifier.classify(CardIds.parse("SA@0 HA@0 S2@0 D2@0 S3@0 H3@0"), PROFILE);
        assertEquals(MoveKind.PAIR_RUN, pairRun.get(0).kind());
        assertEquals(3, pairRun.get(0).mainRank(), "AA2233 高端点为 3");

        List<Move> highAce = MoveClassifier.classify(CardIds.parse("S10@0 HJ@0 DQ@0 CK@0 SA@1"), PROFILE);
        assertEquals(14, highAce.get(0).mainRank(), "10JQKA 高端点为 A(14)");
    }

    @Test
    void test_wildcard_never_substitutes_joker() {
        assertTrue(MoveClassifier.classify(CardIds.parse("H2@0 H2@1 JOKER_SMALL@0"), PROFILE).isEmpty(),
                "P07：百搭不能补成三张王");
        assertTrue(MoveClassifier.classify(CardIds.parse("H2@0 JOKER_SMALL@0"), PROFILE).isEmpty(),
                "百搭不能与小王成对");
        assertTrue(MoveClassifier.classify(CardIds.parse("JOKER_SMALL@0 JOKER_SMALL@1 JOKER_BIG@0 H2@0"), PROFILE).isEmpty(),
                "R17：百搭不能补成天王炸弹");

        List<Move> single = MoveClassifier.classify(CardIds.parse("H2@0"), PROFILE);
        assertEquals(1, single.size());
        assertEquals(MoveKind.SINGLE, single.get(0).kind());
        assertEquals(2, single.get(0).mainRank(), "R03：红桃2单出只是级牌单张，不是王");
    }

    @Test
    void test_ambiguous_straight_keeps_both_endpoints() {
        List<Move> moves = MoveClassifier.classify(CardIds.parse("S3@0 S4@0 S5@0 S6@0 H2@0"), PROFILE);
        Set<String> keys = new TreeSet<>();
        for (Move m : moves) {
            keys.add(semanticKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertEquals(Set.of("STRAIGHT/5/6", "STRAIGHT/5/7", "STRAIGHT_FLUSH/5/6", "STRAIGHT_FLUSH/5/7"),
                keys, "P06：两个端点的顺子与同花顺声明都保留");
    }

    @Test
    void test_pure_wild_pair_is_level_pair() {
        List<Move> moves = MoveClassifier.classify(CardIds.parse("H2@0 H2@1"), PROFILE);
        assertEquals(1, moves.size(), "P05：纯百搭对子只有一个声明");
        Move m = moves.get(0);
        assertEquals(MoveKind.PAIR, m.kind());
        assertEquals(2, m.length());
        assertEquals(2, m.mainRank(), "级牌对子");
        assertTrue(m.wildBindings().isEmpty(), "纯百搭对子无替代绑定");
    }

    private static String semanticKey(String kind, int length, int mainRank) {
        return kind + "/" + length + "/" + mainRank;
    }

    private static List<Integer> sortedCopy(List<Integer> ids) {
        List<Integer> copy = new ArrayList<>(ids);
        Collections.sort(copy);
        return copy;
    }

    private static List<String> expectedBindings(JsonNode bindings) {
        List<String> out = new ArrayList<>();
        if (bindings == null) {
            return out;
        }
        for (JsonNode wb : bindings) {
            int cardId = CardIds.parseOne(wb.get("card_symbol").asText());
            int rank = wb.get("rank").asInt();
            String suit = wb.get("suit").isNull() ? "null" : wb.get("suit").asText();
            out.add(cardId + "->" + rank + ":" + suit);
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> actualBindings(List<WildBinding> bindings) {
        List<String> out = new ArrayList<>();
        for (WildBinding wb : bindings) {
            out.add(wb.cardId() + "->" + wb.rank() + ":" + (wb.suit() == null ? "null" : wb.suit().name()));
        }
        Collections.sort(out);
        return out;
    }
}
