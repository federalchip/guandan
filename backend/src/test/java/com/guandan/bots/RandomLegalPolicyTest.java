package com.guandan.bots;

import com.guandan.domain.CardIds;
import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.ActionKind;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.PlayerView;
import com.guandan.engine.GameEngine;
import com.guandan.engine.PlayerViewFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6 随机合法机器人测试。策略只在供给的合法动作中均匀选择；可见信息边界由函数参数与源码依赖检查确认。
 */
class RandomLegalPolicyTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();

    @Test
    void test_bot_action_is_in_supplied_legal_actions() {
        GameState s = GameEngine.newGame(7L, PROFILE);
        int cp = s.currentPlayer();
        List<Action> legal = GameEngine.getLegalActions(s, cp);
        PlayerView view = PlayerViewFactory.getPlayerView(s, cp);
        assertFalse(legal.isEmpty());
        for (int i = 0; i < 64; i++) {
            Action a = RandomLegalPolicy.chooseAction(view, legal, new SplittableRandom(i));
            assertTrue(legal.contains(a), "机器人动作必须在供给的合法动作集合中");
        }
    }

    @Test
    void test_bot_cannot_pass_on_lead() {
        GameState s = GameEngine.newGame(3L, PROFILE);
        int cp = s.currentPlayer();
        List<Action> legal = GameEngine.getLegalActions(s, cp);
        assertTrue(legal.stream().noneMatch(a -> a.kind() == ActionKind.PASS), "领出供给不含 PASS");
        PlayerView view = PlayerViewFactory.getPlayerView(s, cp);
        for (int i = 0; i < 32; i++) {
            Action a = RandomLegalPolicy.chooseAction(view, legal, new SplittableRandom(i));
            assertEquals(ActionKind.PLAY, a.kind(), "领出时机器人不能过牌");
        }
    }

    @Test
    void test_bot_passes_when_no_response_exists() {
        // 0 领出单张 A；1 只有黑桃3，压不过 → 供给只含 PASS
        GameState lead = stateWithHands(List.of(
                CardIds.parse("SA@0"), CardIds.parse("S3@0"), CardIds.parse("S5@0"), CardIds.parse("S7@0")));
        GameState following = GameEngine.applyAction(lead, 0, Action.play(CardIds.parse("SA@0"), null)).state();
        assertEquals(1, following.currentPlayer());
        List<Action> legal = GameEngine.getLegalActions(following, 1);
        assertEquals(1, legal.size(), "无可压动作时只供 PASS");
        assertEquals(ActionKind.PASS, legal.get(0).kind());
        PlayerView view = PlayerViewFactory.getPlayerView(following, 1);
        for (int i = 0; i < 16; i++) {
            assertEquals(ActionKind.PASS,
                    RandomLegalPolicy.chooseAction(view, legal, new SplittableRandom(i)).kind(),
                    "无跟牌动作时机器人只能过牌");
        }
    }

    @Test
    void test_same_rng_seed_reproduces_choices() {
        GameState s = GameEngine.newGame(11L, PROFILE);
        int cp = s.currentPlayer();
        List<Action> legal = GameEngine.getLegalActions(s, cp);
        PlayerView view = PlayerViewFactory.getPlayerView(s, cp);
        SplittableRandom r1 = new SplittableRandom(999L);
        SplittableRandom r2 = new SplittableRandom(999L);
        List<Action> seqA = new ArrayList<>();
        List<Action> seqB = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            seqA.add(RandomLegalPolicy.chooseAction(view, legal, r1));
            seqB.add(RandomLegalPolicy.chooseAction(view, legal, r2));
        }
        assertEquals(seqA, seqB, "同 seed 复现相同选择序列");
    }

    @Test
    void test_bot_seeds_are_deterministic_and_distinct() {
        assertEquals(BotSeeds.derive(5L, 2L, 1), BotSeeds.derive(5L, 2L, 1), "同输入同种子");
        Set<Long> seeds = new HashSet<>();
        for (int seat = 0; seat < 4; seat++) {
            for (long v = 0; v < 8; v++) {
                seeds.add(BotSeeds.derive(5L, v, seat));
            }
        }
        assertEquals(32, seeds.size(), "不同 version/座位派生不同种子");
    }

    @Test
    void test_empty_legal_actions_is_rejected() {
        PlayerView view = PlayerViewFactory.getPlayerView(GameEngine.newGame(1L, PROFILE), 0);
        assertThrows(IllegalArgumentException.class,
                () -> RandomLegalPolicy.chooseAction(view, List.of(), new SplittableRandom(0)));
    }

    @Test
    void test_policy_has_no_forbidden_dependencies() throws Exception {
        Path src = Path.of("src/main/java/com/guandan/bots/RandomLegalPolicy.java");
        assertTrue(Files.exists(src), "应能从模块根定位策略源码: " + src.toAbsolutePath());
        List<String> imports = Files.readAllLines(src).stream()
                .map(String::trim)
                .filter(l -> l.startsWith("import "))
                .toList();
        assertTrue(imports.stream().noneMatch(i -> i.contains("com.guandan.service")),
                "策略不导入 service: " + imports);
        assertTrue(imports.stream().noneMatch(i -> i.contains("com.guandan.engine")),
                "策略不导入 engine: " + imports);
        assertTrue(imports.stream().noneMatch(i -> i.contains("GameStore")),
                "策略不依赖存储: " + imports);
        assertTrue(imports.stream().noneMatch(i -> i.contains("GameState")),
                "策略不接收 GameState（只接收 PlayerView）: " + imports);
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
}
