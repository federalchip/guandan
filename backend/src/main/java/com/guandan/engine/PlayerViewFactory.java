package com.guandan.engine;

import com.guandan.domain.Deck;
import com.guandan.domain.model.Card;
import com.guandan.domain.model.GameState;
import com.guandan.domain.model.Phase;
import com.guandan.domain.model.PlayerView;
import com.guandan.domain.model.PublicTrick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 隐藏信息过滤：由完整 {@link GameState} 生成面向单个玩家的 {@link PlayerView}。
 * 只暴露本人手牌实体、四家剩余张数与公开桌面牌，不含其他人手牌，也不含服务端种子。
 */
public final class PlayerViewFactory {

    private static final Map<Integer, Card> CARD_BY_ID;

    static {
        Map<Integer, Card> map = new HashMap<>();
        for (Card c : Deck.buildDeck()) {
            map.put(c.cardId(), c);
        }
        CARD_BY_ID = Map.copyOf(map);
    }

    private PlayerViewFactory() {
    }

    public static PlayerView getPlayerView(GameState state, int playerId) {
        List<Integer> ownIds = state.hands().get(playerId);
        List<Card> ownHand = new ArrayList<>(ownIds.size());
        for (int id : ownIds) {
            ownHand.add(CARD_BY_ID.get(id));
        }

        List<Integer> remainingCounts = new ArrayList<>(state.hands().size());
        for (List<Integer> h : state.hands()) {
            remainingCounts.add(h.size());
        }

        PublicTrick publicTrick = null;
        if (state.trick() != null) {
            List<Card> cards = new ArrayList<>();
            for (int id : state.trick().move().cardIds()) {
                cards.add(CARD_BY_ID.get(id));
            }
            publicTrick = new PublicTrick(state.trick().ownerId(), state.trick().move(), cards);
        }

        boolean isLead = state.trick() == null;
        boolean canPass = state.phase() == Phase.PLAYING
                && Objects.equals(state.currentPlayer(), playerId)
                && state.trick() != null;

        return new PlayerView(ownHand, remainingCounts, publicTrick, state.finishOrder(),
                state.currentPlayer(), isLead, canPass, state.profile().profileId(),
                state.profile().levelRank(), state.phase(), state.result(), state.version());
    }
}
