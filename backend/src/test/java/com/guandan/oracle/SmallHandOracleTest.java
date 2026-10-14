package com.guandan.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guandan.domain.CardIds;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import com.guandan.domain.model.WildBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4a 独立慢速参照的验收测试。地面真值仅来自人工 fixture（fixtures/rule_cases.json），
 * 不通过与生产 {@code MoveClassifier}/{@code MoveComparator} 对照来断言，以保持参照独立性。
 */
class SmallHandOracleTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final JsonNode ROOT;

    static {
        try (InputStream in = SmallHandOracleTest.class.getResourceAsStream("/fixtures/rule_cases.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: /fixtures/rule_cases.json");
            }
            ROOT = new ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static List<JsonNode> casesOfType(String type) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode c : ROOT.get("cases")) {
            if (type.equals(c.get("type").asText())) {
                out.add(c);
            }
        }
        return out;
    }

    private static JsonNode caseById(String id) {
        for (JsonNode c : ROOT.get("cases")) {
            if (id.equals(c.get("case_id").asText())) {
                return c;
            }
        }
        throw new IllegalArgumentException("no case " + id);
    }

    static Stream<Arguments> declarationIds() {
        return casesOfType("declaration").stream()
                .map(c -> Arguments.of(c.get("case_id").asText()));
    }

    static Stream<Arguments> errorIds() {
        return casesOfType("error").stream()
                .map(c -> Arguments.of(c.get("case_id").asText()));
    }

    private static List<Integer> selectedIds(JsonNode node) {
        List<Integer> ids = new ArrayList<>();
        if (node.has("selected")) {
            for (JsonNode s : node.get("selected")) {
                ids.add(CardIds.parseOne(s.asText()));
            }
        }
        if (node.has("selected_ids")) {
            for (JsonNode s : node.get("selected_ids")) {
                ids.add(s.asInt());
            }
        }
        return ids;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declarationIds")
    void test_oracle_classify_matches_manual_declarations(String caseId) {
        JsonNode c = caseById(caseId);
        List<Integer> ids = selectedIds(c);
        List<Move> actual = SmallHandOracle.classify(ids, PROFILE);

        Set<String> expected = new TreeSet<>();
        for (JsonNode d : c.get("expected_declarations")) {
            expected.add(semKey(d.get("move_kind").asText(), d.get("length").asInt(), d.get("main_rank").asInt()));
        }
        Set<String> actualKeys = new TreeSet<>();
        for (Move m : actual) {
            actualKeys.add(semKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertEquals(expected, actualKeys, caseId + " 参照完整语义声明集合: " + c.path("note").asText());

        for (JsonNode exp : c.get("expected_declarations")) {
            String key = semKey(exp.get("move_kind").asText(), exp.get("length").asInt(), exp.get("main_rank").asInt());
            Move m = actual.stream()
                    .filter(x -> semKey(x.kind().name(), x.length(), x.mainRank()).equals(key))
                    .findFirst().orElse(null);
            assertNotNull(m, caseId + " 参照缺少声明 " + key);
            assertEquals(expectedBindings(exp.get("wild_bindings")), actualBindings(m.wildBindings()),
                    caseId + " " + key + " wild_bindings");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorIds")
    void test_oracle_rejects_invalid_input(String caseId) {
        JsonNode c = caseById(caseId);
        List<Integer> ids = selectedIds(c);
        String expected = c.get("expected_error").asText();
        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> SmallHandOracle.classify(ids, PROFILE), caseId);
        assertEquals(expected, ex.code(), caseId + " 参照错误码");
    }

    @Test
    void test_oracle_low_ace_and_level_natural_order() {
        Move low = onlySmallHand("SA@0 S2@0 D3@0 C4@0 H5@0");
        assertEquals(MoveKind.STRAIGHT, low.kind());
        assertEquals(5, low.mainRank(), "A2345 高端点 5");

        Move high = onlySmallHand("S10@0 HJ@0 DQ@0 CK@0 SA@1");
        assertEquals(14, high.mainRank(), "10JQKA 高端点 A");
    }

    @Test
    void test_oracle_wildcard_never_substitutes_joker() {
        assertTrue(SmallHandOracle.classify(CardIds.parse("H2@0 H2@1 JOKER_SMALL@0"), PROFILE).isEmpty());
        assertTrue(SmallHandOracle.classify(CardIds.parse("H2@0 JOKER_SMALL@0"), PROFILE).isEmpty());
        Move single = onlySmallHand("H2@0");
        assertEquals(MoveKind.SINGLE, single.kind());
        assertEquals(2, single.mainRank(), "红桃2单出=级牌单张");
    }

    @Test
    void test_oracle_pure_wild_pair_is_level_pair() {
        List<Move> moves = SmallHandOracle.classify(CardIds.parse("H2@0 H2@1"), PROFILE);
        assertEquals(1, moves.size());
        assertEquals(MoveKind.PAIR, moves.get(0).kind());
        assertEquals(2, moves.get(0).mainRank());
        assertTrue(moves.get(0).wildBindings().isEmpty());
    }

    @Test
    void test_oracle_ambiguous_straight_keeps_both_endpoints() {
        List<Move> moves = SmallHandOracle.classify(CardIds.parse("S3@0 S4@0 S5@0 S6@0 H2@0"), PROFILE);
        Set<String> keys = new TreeSet<>();
        for (Move m : moves) {
            keys.add(semKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertEquals(Set.of("STRAIGHT/5/6", "STRAIGHT/5/7", "STRAIGHT_FLUSH/5/6", "STRAIGHT_FLUSH/5/7"), keys);
    }

    // ---- generateMoves 属性 ----

    @Test
    void test_generate_moves_lead_covers_all_singles() {
        List<Integer> hand = CardIds.parse("S5@0 SK@0 SA@0");
        List<Move> moves = SmallHandOracle.generateMoves(hand, null, PROFILE);
        Set<String> singleRanks = new HashSet<>();
        for (Move m : moves) {
            if (m.kind() == MoveKind.SINGLE) {
                singleRanks.add(String.valueOf(m.mainRank()));
            }
        }
        assertEquals(Set.of("5", "13", "14"), singleRanks, "领出时三张不同单张都可出");
        for (Move m : moves) {
            assertTrue(hand.containsAll(m.cardIds()), "候选牌来自手牌");
            assertEquals(new HashSet<>(m.cardIds()).size(), m.cardIds().size(), "候选无重复实体");
        }
    }

    @Test
    void test_generate_moves_follow_only_beating() {
        List<Integer> hand = CardIds.parse("S5@0 SK@0 SA@0");
        Move previous = onlySmallHand("S10@0"); // 单张 10
        List<Move> moves = SmallHandOracle.generateMoves(hand, previous, PROFILE);
        Set<String> kinds = new TreeSet<>();
        for (Move m : moves) {
            kinds.add(semKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertTrue(kinds.contains("SINGLE/1/13"), "K 单张压过 10");
        assertTrue(kinds.contains("SINGLE/1/14"), "A 单张压过 10");
        assertFalse(kinds.contains("SINGLE/1/5"), "5 单张压不过 10，应被过滤");
    }

    @Test
    void test_generate_moves_bomb_beats_high_single() {
        List<Integer> hand = CardIds.parse("S9@0 H9@0 D9@0 C9@0 S3@0");
        Move previous = onlySmallHand("SA@0"); // 单张 A
        List<Move> moves = SmallHandOracle.generateMoves(hand, previous, PROFILE);
        Set<String> kinds = new TreeSet<>();
        for (Move m : moves) {
            kinds.add(semKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertEquals(Set.of("BOMB/4/9"), kinds, "只有 9 炸弹能压单张 A；无单张可压");
    }

    @Test
    void test_generate_moves_tiny_hand_exact_semantics() {
        List<Integer> hand = CardIds.parse("S3@0 H4@0");
        List<Move> moves = SmallHandOracle.generateMoves(hand, null, PROFILE);
        Set<String> kinds = new TreeSet<>();
        for (Move m : moves) {
            kinds.add(semKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertEquals(Set.of("SINGLE/1/3", "SINGLE/1/4"), kinds);
    }

    @Test
    void test_generate_moves_merges_equivalent_copies() {
        // 两张同花同点（黑桃3 的两副副本）：单张语义应归并为一个代表
        List<Integer> hand = CardIds.parse("S3@0 S3@1");
        List<Move> moves = SmallHandOracle.generateMoves(hand, null, PROFILE);
        long singleThrees = moves.stream()
                .filter(m -> m.kind() == MoveKind.SINGLE && m.mainRank() == 3)
                .count();
        assertEquals(1, singleThrees, "等价副本（同面）单张归并为一个代表");
        Set<String> kinds = new TreeSet<>();
        for (Move m : moves) {
            kinds.add(semKey(m.kind().name(), m.length(), m.mainRank()));
        }
        assertEquals(Set.of("SINGLE/1/3", "PAIR/2/3"), kinds);
    }

    @Test
    void test_generate_moves_keeps_distinct_faces_separate() {
        // 黑桃3 与 红桃3 是不同牌面：两个单张代表都应保留
        List<Integer> hand = CardIds.parse("S3@0 H3@0");
        List<Move> moves = SmallHandOracle.generateMoves(hand, null, PROFILE);
        long singleThrees = moves.stream()
                .filter(m -> m.kind() == MoveKind.SINGLE && m.mainRank() == 3)
                .count();
        assertEquals(2, singleThrees, "不同花色的同点单张不合并");
    }

    // ---- 依赖隔离检查 ----

    @Test
    void test_oracle_source_does_not_reference_production_rules() throws Exception {
        Path src = Path.of("src/test/java/com/guandan/oracle/SmallHandOracle.java");
        assertTrue(Files.exists(src), "应能从模块根定位参照源码: " + src.toAbsolutePath());
        String code = Files.readString(src);
        for (String forbidden : List.of("MoveClassifier", "MoveComparator", "LegalMoveGenerator",
                "com.guandan.rules")) {
            assertFalse(code.contains(forbidden),
                    "独立参照不得引用生产代码: " + forbidden);
        }
    }

    // ---- helpers ----

    private Move onlySmallHand(String symbols) {
        List<Move> moves = SmallHandOracle.classify(CardIds.parse(symbols), PROFILE);
        assertEquals(1, moves.size(), "expected single declaration for: " + symbols);
        return moves.get(0);
    }

    private static String semKey(String kind, int length, int mainRank) {
        return kind + "/" + length + "/" + mainRank;
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
