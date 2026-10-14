package com.guandan.rules;

import com.guandan.domain.CardIds;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegalMoveGeneratorTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();

    private static final String[] HANDS = {
            "S3@0 H4@0 D5@0",
            "S3@0 H3@0 D3@0 C3@0",
            "S3@0 S4@0 S5@0 S6@0 S7@0",
            "S3@0 H3@0 S4@0 H4@0 S5@0 H5@0",
            "S3@0 H3@0 D3@0 H2@0 H2@1",
            "S3@0 S4@0 S5@0 H2@0 H2@1",
            "JOKER_SMALL@0 JOKER_BIG@0 S3@0 H3@0",
            "S9@0 H9@0 D9@0 C9@0 S3@0 H4@0",
            "S3@0 S3@1 H3@0 D4@0 H2@0",
    };

    @Test
    void test_generated_moves_are_owned_and_valid() {
        for (String h : HANDS) {
            List<Integer> hand = CardIds.parse(h);
            List<Move> moves = LegalMoveGenerator.generateLegalMoves(hand, null, PROFILE);
            assertFalse(moves.isEmpty(), "领出至少有一个候选: " + h);
            for (Move m : moves) {
                assertTrue(hand.containsAll(m.cardIds()), "候选牌属于手牌: " + h);
                assertEquals(new HashSet<>(m.cardIds()).size(), m.cardIds().size(),
                        "候选无重复实体: " + h);
                // 候选必须是其自身所选牌的一个有效声明
                List<Move> reclassified = MoveClassifier.classify(m.cardIds(), PROFILE);
                boolean valid = reclassified.stream().anyMatch(x ->
                        x.kind() == m.kind() && x.mainRank() == m.mainRank() && x.length() == m.length());
                assertTrue(valid, "候选是合法声明: " + h + " -> " + m.kind() + "/" + m.length() + "/" + m.mainRank());
            }
        }
    }

    @Test
    void test_response_moves_all_beat_previous() {
        Move prevSingle = single("S10@0");
        Move prevPair = firstOf("C10@0 C10@1");
        for (String h : HANDS) {
            List<Integer> hand = CardIds.parse(h);
            for (Move prev : List.of(prevSingle, prevPair)) {
                List<Move> moves = LegalMoveGenerator.generateLegalMoves(hand, prev, PROFILE);
                for (Move m : moves) {
                    assertTrue(MoveComparator.canBeat(m, prev, PROFILE),
                            "跟牌候选必须压过桌面: " + h + " vs " + prev.kind() + "/" + prev.mainRank()
                                    + " 候选 " + m.kind() + "/" + m.mainRank());
                }
            }
        }
    }

    @Test
    void test_generator_preserves_suit_distinct_consumptions() {
        List<Integer> hand = CardIds.parse("S3@0 H3@0 D5@0");
        List<Move> moves = LegalMoveGenerator.generateLegalMoves(hand, null, PROFILE);
        Set<String> singleThreeFaces = new TreeSet<>();
        for (Move m : moves) {
            if (m.kind() == MoveKind.SINGLE && m.mainRank() == 3) {
                singleThreeFaces.add(MoveSigs.sig(m));
            }
        }
        assertEquals(2, singleThreeFaces.size(),
                "黑桃3 与 红桃3 是不同牌面消耗，单张候选应各保留一个: " + singleThreeFaces);
    }

    @Test
    void test_identical_copies_have_one_representative() {
        List<Integer> hand = CardIds.parse("S3@0 S3@1 D5@0");
        List<Move> moves = LegalMoveGenerator.generateLegalMoves(hand, null, PROFILE);
        Set<String> singleThreeFaces = new TreeSet<>();
        for (Move m : moves) {
            if (m.kind() == MoveKind.SINGLE && m.mainRank() == 3) {
                singleThreeFaces.add(MoveSigs.sig(m));
            }
        }
        assertEquals(1, singleThreeFaces.size(),
                "两副黑桃3 是完全相同牌面，单张只保留一个代表: " + singleThreeFaces);
    }

    @Test
    void test_wildcards_used_at_most_once() {
        List<Integer> hand = CardIds.parse("H2@0 H2@1 S3@0 H3@0 D4@0");
        Set<Integer> wildIds = new HashSet<>(CardIds.parse("H2@0 H2@1"));
        List<Move> moves = LegalMoveGenerator.generateLegalMoves(hand, null, PROFILE);
        for (Move m : moves) {
            List<Integer> ids = m.cardIds();
            assertEquals(new HashSet<>(ids).size(), ids.size(), "候选无重复实体");
            long wildUsed = ids.stream().filter(wildIds::contains).count();
            assertTrue(wildUsed <= 2, "百搭最多用两张: " + wildUsed);
            // 百搭绑定数不超过实际消耗的百搭实体数
            assertTrue(m.wildBindings().size() <= wildUsed,
                    "绑定数不超过消耗的百搭数");
        }
    }

    @Test
    void test_lead_always_has_a_single() {
        for (String h : HANDS) {
            List<Integer> hand = CardIds.parse(h);
            List<Move> moves = LegalMoveGenerator.generateLegalMoves(hand, null, PROFILE);
            assertTrue(moves.stream().anyMatch(m -> m.kind() == MoveKind.SINGLE),
                    "领出时至少存在一个单张候选: " + h);
        }
    }

    @Test
    void test_no_pass_and_stable_order() {
        List<Integer> hand = CardIds.parse("S3@0 H3@0 D4@0 C5@0");
        List<Move> a = LegalMoveGenerator.generateLegalMoves(hand, null, PROFILE);
        List<Move> b = LegalMoveGenerator.generateLegalMoves(hand, null, PROFILE);
        assertEquals(MoveSigs.sigSet(a), MoveSigs.sigSet(b), "同手牌两次生成语义集合一致");
        List<String> orderA = new ArrayList<>();
        for (Move m : a) orderA.add(MoveSigs.sig(m));
        List<String> orderB = new ArrayList<>();
        for (Move m : b) orderB.add(MoveSigs.sig(m));
        assertEquals(orderA, orderB, "候选顺序稳定，不依赖哈希遍历次序");
        // 生成器只产出 PLAY 候选，不含过牌
        for (Move m : a) {
            assertFalse(m.cardIds().isEmpty(), "PLAY 候选至少一张牌");
        }
    }

    @Test
    void test_generator_rejects_out_of_range_card_id() {
        GameRuleException high = assertThrows(GameRuleException.class,
                () -> LegalMoveGenerator.generateLegalMoves(List.of(108), null, PROFILE));
        assertEquals("CARD_ID_OUT_OF_RANGE", high.code());
        GameRuleException negative = assertThrows(GameRuleException.class,
                () -> LegalMoveGenerator.generateLegalMoves(List.of(-1), null, PROFILE));
        assertEquals("CARD_ID_OUT_OF_RANGE", negative.code());
    }

    @Test
    void test_generator_rejects_duplicate_card_id() {
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> LegalMoveGenerator.generateLegalMoves(List.of(4, 4), null, PROFILE));
        assertEquals("DUPLICATE_CARD_ID", ex.code());
    }

    @Test
    void test_generator_error_codes_match_classifier() {
        // 生成器与解析器对同一非法输入抛出一致的规则错误码（T7 统一映射 422）
        for (List<Integer> bad : List.of(List.of(108), List.of(-1), List.of(4, 4))) {
            GameRuleException fromClassifier = assertThrows(GameRuleException.class,
                    () -> MoveClassifier.classify(bad, PROFILE));
            GameRuleException fromGenerator = assertThrows(GameRuleException.class,
                    () -> LegalMoveGenerator.generateLegalMoves(bad, null, PROFILE));
            assertEquals(fromClassifier.code(), fromGenerator.code(),
                    "非法输入 " + bad + " 两处错误码一致");
        }
    }

    private static Move single(String symbol) {
        return firstOf(symbol);
    }

    private static Move firstOf(String symbols) {
        List<Move> moves = MoveClassifier.classify(CardIds.parse(symbols), PROFILE);
        assertEquals(1, moves.size(), "expected single declaration for " + symbols);
        return moves.get(0);
    }
}
