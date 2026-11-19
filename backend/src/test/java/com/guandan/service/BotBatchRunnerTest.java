package com.guandan.service;

import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.Trick;
import com.guandan.bots.RandomLegalPolicy;
import com.guandan.engine.GameEngine;
import com.guandan.rules.MoveClassifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批次执行器纯单元测试：私有状态推进、事件序号连续、512 步保护。 */
class BotBatchRunnerTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();
    private static final long SEED = 42L;

    @Test
    void test_run_applies_human_then_bots_with_contiguous_seq() {
        var single5 = MoveClassifier.classify(CardIds.parse("S5@0"), PROFILE).get(0);
        GameState follow = stateWithHands(
                List.of(CardIds.parse("S3@0 H4@0"), CardIds.parse("H6@0"),
                        CardIds.parse("D7@0"), CardIds.parse("C8@0")),
                0, new Trick(1, single5, Set.of(0, 2, 3)), List.of(), Phase.PLAYING, null, 5L);
        BotBatchRunner runner = new BotBatchRunner(RandomLegalPolicy::chooseAction, GameEngine::applyAction);

        BotBatchRunner.BatchResult r = runner.run(follow, Action.pass(), SEED, 7L);

        assertEquals(6L, r.acceptedVersion(), "accepted_version 为真人单动作后的版本");
        assertFalse(r.events().isEmpty());
        assertEquals(7L, r.events().get(0).seq(), "事件序号从 startSeq 开始");
        for (int i = 1; i < r.events().size(); i++) {
            assertEquals(r.events().get(i - 1).seq() + 1, r.events().get(i).seq(), "seq 连续");
        }
        assertEquals(7L + r.events().size(), r.nextSeq());
        assertTrue(r.state().version() >= 6L);
        assertEquals(5L, follow.version(), "入参状态不被修改（私有工作副本）");
    }

    @Test
    void test_step_limit_throws_without_commit() {
        GameState stuck = stateWithHands(
                List.of(List.of(4), List.of(8), List.of(12), List.of(16)),
                1, null, List.of(), Phase.PLAYING, null, 0L);
        ActionAdvancer noAdvance = (st, player, action) -> new EngineOutcome(stuck, List.of());
        BotBatchRunner runner = new BotBatchRunner(RandomLegalPolicy::chooseAction, noAdvance);
        GameState initial = GameEngine.newGame(SEED, PROFILE);
        List<com.guandan.domain.model.Move> choices =
                GameEngine.resolveSelection(initial, 0, List.of(initial.hands().get(0).get(0)));
        Action human = Action.play(choices.get(0).cardIds(), choices.get(0).interpretationKey());

        GameRuleException ex = assertThrows(GameRuleException.class,
                () -> runner.run(initial, human, SEED, 1L));
        assertEquals("BOT_STEP_LIMIT", ex.code());
    }

    private static GameState stateWithHands(List<List<Integer>> hands, Integer currentPlayer, Trick trick,
                                            List<Integer> finishOrder, Phase phase,
                                            com.guandan.domain.model.GameResult result, long version) {
        Set<Integer> union = new HashSet<>();
        for (List<Integer> h : hands) union.addAll(h);
        List<Integer> discarded = new ArrayList<>();
        for (int id = 0; id < Deck.TOTAL_CARDS; id++) {
            if (!union.contains(id)) discarded.add(id);
        }
        return new GameState(PROFILE, hands, discarded, currentPlayer, trick, finishOrder, phase, result, version);
    }
}
