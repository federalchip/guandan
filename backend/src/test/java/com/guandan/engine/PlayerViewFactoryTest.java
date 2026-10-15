package com.guandan.engine;

import com.guandan.domain.CardIds;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Action;
import com.guandan.domain.model.Card;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.PlayerView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 隐藏信息过滤测试：PlayerView 只暴露本人手牌、四家剩余张数与公开桌面牌，
 * 不含其他人手牌，也不含服务端种子。
 */
class PlayerViewFactoryTest {

    private static final RuleProfile PROFILE = RuleProfile.v1();

    @Test
    void test_view_hides_opponent_cards_and_seed() {
        GameState s = GameEngine.newGame(4242L, PROFILE);
        PlayerView v0 = PlayerViewFactory.getPlayerView(s, 0);

        Set<Integer> own = new HashSet<>(s.hands().get(0));
        Set<Integer> opponents = new HashSet<>();
        for (int p = 1; p < 4; p++) {
            opponents.addAll(s.hands().get(p));
        }
        assertEquals(27, v0.ownHand().size());
        for (Card c : v0.ownHand()) {
            assertTrue(own.contains(c.cardId()), "视图只含本人手牌: " + c.cardId());
            assertFalse(opponents.contains(c.cardId()), "视图不含对手手牌: " + c.cardId());
        }
        assertEquals(List.of(27, 27, 27, 27), v0.remainingCounts(), "只暴露剩余张数，不暴露对手牌");

        for (RecordComponent rc : PlayerView.class.getRecordComponents()) {
            assertFalse(rc.getName().toLowerCase().contains("seed"), "PlayerView 不含种子字段");
        }
        assertFalse(Arrays_containsField(s, "seed"), "GameState 不持久化种子");

        assertEquals(RuleProfile.V1_PROFILE_ID, v0.profileId());
        assertEquals(2, v0.levelRank());
        assertEquals(Phase.PLAYING, v0.phase());
        assertNull(v0.result());
        assertEquals(0L, v0.version());
    }

    @Test
    void test_view_lead_and_follow_flags() {
        GameState lead = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        PlayerView v0 = PlayerViewFactory.getPlayerView(lead, 0);
        assertTrue(v0.isLead(), "trick 为空即领出");
        assertFalse(v0.canPass(), "领出不能过牌");
        assertNull(v0.trick());

        GameState following = GameEngine.applyAction(lead, 0, Action.play(CardIds.parse("S3@0"), null)).state();
        PlayerView v1 = PlayerViewFactory.getPlayerView(following, 1);
        assertFalse(v1.isLead(), "跟牌非领出");
        assertTrue(v1.canPass(), "轮到自己跟牌可过");
        assertEquals(0, v1.trick().ownerId());
        assertEquals(CardIds.parse("S3@0").size(), v1.trick().cards().size());

        PlayerView v0Follow = PlayerViewFactory.getPlayerView(following, 0);
        assertFalse(v0Follow.canPass(), "非当前玩家 canPass=false");
    }

    @Test
    void test_view_remaining_counts_track_play() {
        GameState lead = TurnFixtures.initialState(TurnFixtures.scenario("S04"));
        GameState after = GameEngine.applyAction(lead, 0, Action.play(CardIds.parse("S3@0"), null)).state();
        PlayerView v = PlayerViewFactory.getPlayerView(after, 0);
        assertEquals(List.of(1, 1, 1, 1), v.remainingCounts(), "0 出一张后剩 1，其余各 1");
        assertEquals(1, v.ownHand().size());
    }

    private static boolean Arrays_containsField(Object o, String name) {
        for (RecordComponent rc : o.getClass().getRecordComponents()) {
            if (rc.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
