package com.guandan.rules;

import com.guandan.domain.CardIds;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Move;
import com.guandan.oracle.SmallHandOracle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T4b：生产生成器与独立慢速参照在小手牌（≤10）上的双向语义集合对照。
 * 规范化签名 = (牌型, 张数, 主比较点数, 消耗实体牌面多重集)。失败分别列出遗漏与多余。
 */
class GeneratorOracleTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();

    static Stream<Arguments> cases() {
        return Stream.of(
                // 领出（previous = ""）
                Arguments.of("lead-simple", "S3@0 H4@0 D5@0", ""),
                Arguments.of("lead-triple", "S3@0 H3@0 D3@0", ""),
                Arguments.of("lead-pair-run", "S3@0 H3@0 S4@0 H4@0 S5@0 H5@0", ""),
                Arguments.of("lead-natural-straight-flush", "S3@0 S4@0 S5@0 S6@0 S7@0", ""),
                Arguments.of("lead-triple-two-wilds", "S3@0 H3@0 D3@0 H2@0 H2@1", ""),
                Arguments.of("lead-straight-two-wilds", "S3@0 S4@0 S5@0 H2@0 H2@1", ""),
                Arguments.of("lead-straight-one-wild", "S3@0 S4@0 S5@0 S6@0 H2@0", ""),
                Arguments.of("lead-pure-wild-pair", "H2@0 H2@1", ""),
                Arguments.of("lead-single-wild", "H2@0", ""),
                Arguments.of("lead-jokers", "JOKER_SMALL@0 JOKER_BIG@0 S3@0", ""),
                Arguments.of("lead-joker-bomb", "JOKER_SMALL@0 JOKER_SMALL@1 JOKER_BIG@0 JOKER_BIG@1", ""),
                Arguments.of("lead-duplicate-faces", "S3@0 S3@1 H3@0", ""),
                Arguments.of("lead-bomb", "S9@0 H9@0 D9@0 C9@0", ""),
                Arguments.of("lead-fullhouse-material", "S3@0 H3@0 D4@0 C4@0 H2@0", ""),
                Arguments.of("lead-triple-run-two-wilds", "S3@0 H3@0 D3@0 S4@0 H2@0 H2@1", ""),
                Arguments.of("lead-two-pairs-wild", "S3@0 H3@0 S4@0 H2@0", ""),
                // 跟牌
                Arguments.of("follow-single-10", "S5@0 SK@0 SA@0 H9@0", "S10@0"),
                Arguments.of("follow-pair-10", "S9@0 H9@0 D9@0 C9@0 S3@0 H3@0", "C10@0 C10@1"),
                Arguments.of("follow-single-ace", "S3@0 H3@0 D3@0 H2@0", "SA@0"),
                Arguments.of("follow-pair-10-flushy", "S3@0 S4@0 S5@0 S6@0 S7@0 H2@0", "C10@0 C10@1"),
                Arguments.of("follow-level-single", "H2@0 H2@1 S3@0 H4@0", "S2@0")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void test_generator_equals_small_hand_oracle(String name, String handSymbols, String prevSymbols) {
        List<Integer> hand = CardIds.parse(handSymbols);
        Move previous = prevSymbols.isEmpty() ? null : singleDeclaration(prevSymbols);

        List<Move> generated = LegalMoveGenerator.generateLegalMoves(hand, previous, PROFILE);
        List<Move> reference = SmallHandOracle.generateMoves(hand, previous, PROFILE);

        Set<String> genSig = MoveSigs.sigSet(generated);
        Set<String> orcSig = MoveSigs.sigSet(reference);

        if (!genSig.equals(orcSig)) {
            Set<String> missing = new TreeSet<>(orcSig);
            missing.removeAll(genSig);
            Set<String> extra = new TreeSet<>(genSig);
            extra.removeAll(orcSig);
            fail(name + " 生成器与独立参照语义集合不一致"
                    + "\n  遗漏(参照有,生成器无)=" + missing
                    + "\n  多余(生成器有,参照无)=" + extra);
        }
        assertEquals(orcSig, genSig, name);
    }

    private static Move singleDeclaration(String symbols) {
        List<Move> moves = MoveClassifier.classify(CardIds.parse(symbols), PROFILE);
        assertEquals(1, moves.size(), "previous 应唯一声明: " + symbols);
        return moves.get(0);
    }
}
