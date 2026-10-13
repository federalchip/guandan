package com.guandan.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveComparatorTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();

    static Stream<Arguments> comparisonCaseIds() {
        return RuleFixtures.idsOfType("comparison").stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("comparisonCaseIds")
    void test_can_beat_manual_cases(String caseId) {
        JsonNode c = RuleFixtures.BY_ID.get(caseId);
        Move challenger = resolveSide(c.get("challenger"));
        Move previous = resolveSide(c.get("previous"));
        boolean expected = c.get("expected_can_beat").asBoolean();
        assertEquals(expected, MoveComparator.canBeat(challenger, previous, PROFILE),
                caseId + ": " + c.path("note").asText());
    }

    @Test
    void test_bomb_hierarchy() {
        // 4 张炸弹 < 5 张炸弹 < 同花顺 < 6 张炸弹 < 天王炸弹
        assertTrue(MoveComparator.canBeat(bomb(5, 8), bomb(4, 3), PROFILE), "5张炸弹 > 4张炸弹");
        assertTrue(MoveComparator.canBeat(straightFlush(5), bomb(5, 14), PROFILE), "同花顺 > 5张炸弹");
        assertTrue(MoveComparator.canBeat(bomb(6, 3), straightFlush(14), PROFILE), "6张炸弹 > 同花顺");
        assertTrue(MoveComparator.canBeat(jokerBomb(), bomb(6, 9), PROFILE), "天王炸弹 > 6张炸弹");

        assertFalse(MoveComparator.canBeat(bomb(4, 14), bomb(5, 3), PROFILE), "4张炸弹不能压5张炸弹");
        assertFalse(MoveComparator.canBeat(straightFlush(14), bomb(6, 3), PROFILE), "同花顺不能压6张炸弹");
        assertTrue(MoveComparator.canBeat(bomb(10, 3), straightFlush(14), PROFILE), "10张炸弹 > 同花顺");
    }

    @Test
    void test_same_count_bomb_compares_raised_level_rank() {
        assertTrue(MoveComparator.canBeat(bomb(4, 2), bomb(4, 14), PROFILE), "同张数：级牌2炸弹 > A炸弹");
        assertFalse(MoveComparator.canBeat(bomb(4, 14), bomb(4, 2), PROFILE), "A炸弹不能压级牌2炸弹");
        assertFalse(MoveComparator.canBeat(bomb(4, 9), bomb(4, 9), PROFILE), "同张数同点不能压");
    }

    @Test
    void test_normal_different_kind_cannot_beat() {
        assertFalse(MoveComparator.canBeat(single(14), pair(3), PROFILE), "单张不能压对子");
        assertFalse(MoveComparator.canBeat(triple(3), fullHouse(4), PROFILE), "三张不能压三带对");
        assertFalse(MoveComparator.canBeat(pair(14), single(14), PROFILE), "对子不能压单张");
    }

    @Test
    void test_same_kind_same_rank_cannot_beat() {
        assertFalse(MoveComparator.canBeat(single(14), single(14), PROFILE), "同型同点单张不能压");
        assertFalse(MoveComparator.canBeat(pair(9), pair(9), PROFILE), "同型同点对子不能压");
        assertFalse(MoveComparator.canBeat(straightFlush(7), straightFlush(7), PROFILE), "同大小同花顺不能压");
    }

    @Test
    void test_single_level_beats_ace_but_sequence_not_raised() {
        assertTrue(MoveComparator.canBeat(single(2), single(14), PROFILE), "单张级牌2 > 单张A");
        assertTrue(MoveComparator.canBeat(pair(2), pair(14), PROFILE), "级牌对子 > A对子");
        // 序列按自然高端点比较，级牌2不提升：23456(高端点6) > A2345(高端点5)
        assertTrue(MoveComparator.canBeat(straight(6), straight(5), PROFILE), "序列高端点6 > 5");
        assertFalse(MoveComparator.canBeat(straight(5), straight(6), PROFILE), "序列高端点5 不能压 6");
    }

    @Test
    void test_lead_has_nothing_to_beat() {
        assertTrue(MoveComparator.canBeat(single(3), null, PROFILE), "previous 为 null（领出）时任何出牌均可");
    }

    private static Move resolveSide(JsonNode side) {
        List<Integer> ids = RuleFixtures.selectedIds(side);
        String kind = side.get("move_kind").asText();
        int length = side.get("length").asInt();
        int mainRank = side.get("main_rank").asInt();
        return MoveClassifier.classify(ids, PROFILE).stream()
                .filter(m -> m.kind().name().equals(kind) && m.length() == length && m.mainRank() == mainRank)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "classify 未产生 " + kind + "/" + length + "/" + mainRank + "，ids=" + ids));
    }

    private static Move single(int rank) {
        return new Move(List.of(), MoveKind.SINGLE, rank, 1, List.of(), "single-" + rank);
    }

    private static Move pair(int rank) {
        return new Move(List.of(), MoveKind.PAIR, rank, 2, List.of(), "pair-" + rank);
    }

    private static Move triple(int rank) {
        return new Move(List.of(), MoveKind.TRIPLE, rank, 3, List.of(), "triple-" + rank);
    }

    private static Move fullHouse(int rank) {
        return new Move(List.of(), MoveKind.FULL_HOUSE, rank, 5, List.of(), "fh-" + rank);
    }

    private static Move straight(int high) {
        return new Move(List.of(), MoveKind.STRAIGHT, high, 5, List.of(), "straight-" + high);
    }

    private static Move straightFlush(int high) {
        return new Move(List.of(), MoveKind.STRAIGHT_FLUSH, high, 5, List.of(), "sf-" + high);
    }

    private static Move bomb(int count, int rank) {
        return new Move(List.of(), MoveKind.BOMB, rank, count, List.of(), "bomb-" + count + "-" + rank);
    }

    private static Move jokerBomb() {
        return new Move(List.of(), MoveKind.JOKER_BOMB, 16, 4, List.of(), "joker-bomb");
    }
}
