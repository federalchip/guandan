package com.guandan.api.dto;

import com.guandan.domain.model.Card;
import com.guandan.domain.model.DomainEvent;
import com.guandan.domain.model.GameResult;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.PlayerView;
import com.guandan.domain.model.PublicTrick;
import com.guandan.domain.model.WildBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 领域模型 → 传输 DTO 的白名单映射。只映射公开字段，enum 转字符串、List 转数组；
 * payload 直接透传（引擎已保证仅含公开信息）。不暴露 GameState 内部或种子。
 */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static CardView toCardView(Card c) {
        return new CardView(c.cardId(), c.deckIndex(), c.rank(), c.suit().name());
    }

    public static WildBindingView toWildBindingView(WildBinding w) {
        return new WildBindingView(w.cardId(), w.rank(), w.suit() == null ? null : w.suit().name());
    }

    public static MoveView toMoveView(Move m) {
        List<WildBindingView> bindings = new ArrayList<>(m.wildBindings().size());
        for (WildBinding w : m.wildBindings()) {
            bindings.add(toWildBindingView(w));
        }
        return new MoveView(List.copyOf(m.cardIds()), m.kind().name(), m.mainRank(), m.length(),
                List.copyOf(bindings), m.interpretationKey());
    }

    public static List<MoveView> toMoveViews(List<Move> moves) {
        List<MoveView> out = new ArrayList<>(moves.size());
        for (Move m : moves) {
            out.add(toMoveView(m));
        }
        return List.copyOf(out);
    }

    public static ResultView toResultView(GameResult r) {
        List<List<Integer>> groups = new ArrayList<>(r.placementGroups().size());
        for (List<Integer> g : r.placementGroups()) {
            groups.add(List.copyOf(g));
        }
        return new ResultView(r.winnerTeam(), List.copyOf(groups), r.outcome().name());
    }

    public static PublicTrickView toPublicTrickView(PublicTrick t) {
        List<CardView> cards = new ArrayList<>(t.cards().size());
        for (Card c : t.cards()) {
            cards.add(toCardView(c));
        }
        return new PublicTrickView(t.ownerId(), toMoveView(t.move()), List.copyOf(cards));
    }

    public static GameView toGameView(String gameId, PlayerView v) {
        List<CardView> ownHand = new ArrayList<>(v.ownHand().size());
        for (Card c : v.ownHand()) {
            ownHand.add(toCardView(c));
        }
        return new GameView(gameId,
                List.copyOf(ownHand),
                List.copyOf(v.remainingCounts()),
                v.trick() == null ? null : toPublicTrickView(v.trick()),
                List.copyOf(v.finishOrder()),
                v.currentPlayer(),
                v.isLead(),
                v.canPass(),
                v.profileId(),
                v.levelRank(),
                v.phase().name(),
                v.result() == null ? null : toResultView(v.result()),
                v.version());
    }

    public static EventView toEventView(long seq, long stateVersion, DomainEvent e) {
        return new EventView(seq, stateVersion, e.eventType().name(), e.actorId(), e.payload());
    }
}
