package com.guandan.domain;

import com.guandan.domain.model.Card;
import com.guandan.domain.model.Suit;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 108 张实体牌目录、洗牌与发牌。纯 Java，不依赖 Spring/Jackson。
 *
 * <p>实体 ID 公式：普通牌 {@code deckIndex*54 + (rank-2)*4 + suitIndex}（花色顺序 S/H/D/C），
 * 每副第 52、53 位分别为小王、大王。因此红桃 2 的 ID 为 1（deck 0）与 55（deck 1）。
 *
 * <p>{@link #deal(long)} 使用 JDK 17 {@link SplittableRandom} 驱动 Fisher–Yates 洗牌，
 * 在 ID 列表副本上操作，不破坏 {@link #buildDeck()} 的规范顺序；同 seed 可复现。
 */
public final class Deck {

    public static final int DECK_COUNT = 2;
    public static final int CARDS_PER_DECK = 54;
    public static final int TOTAL_CARDS = DECK_COUNT * CARDS_PER_DECK;
    public static final int PLAYER_COUNT = 4;
    public static final int HAND_SIZE = TOTAL_CARDS / PLAYER_COUNT;
    public static final int SMALL_JOKER_RANK = 15;
    public static final int BIG_JOKER_RANK = 16;
    private static final int SMALL_JOKER_OFFSET = 52;
    private static final int BIG_JOKER_OFFSET = 53;
    private static final Suit[] NORMAL_SUITS = {Suit.S, Suit.H, Suit.D, Suit.C};

    private Deck() {
    }

    /** 返回 108 张实体牌，按 cardId 升序 0..107；每次调用返回独立的不可变副本。 */
    public static List<Card> buildDeck() {
        List<Card> cards = new ArrayList<>(TOTAL_CARDS);
        for (int deckIndex = 0; deckIndex < DECK_COUNT; deckIndex++) {
            int base = deckIndex * CARDS_PER_DECK;
            for (int rank = 2; rank <= 14; rank++) {
                for (Suit suit : NORMAL_SUITS) {
                    int cardId = base + (rank - 2) * 4 + suit.ordinal();
                    cards.add(new Card(cardId, deckIndex, rank, suit));
                }
            }
            cards.add(new Card(base + SMALL_JOKER_OFFSET, deckIndex, SMALL_JOKER_RANK, Suit.J));
            cards.add(new Card(base + BIG_JOKER_OFFSET, deckIndex, BIG_JOKER_RANK, Suit.J));
        }
        return List.copyOf(cards);
    }

    /** 洗牌并发四家各 27 张，返回四份不可变手牌（cardId 列表）；不修改规范牌目录。 */
    public static List<List<Integer>> deal(long seed) {
        List<Integer> ids = new ArrayList<>(TOTAL_CARDS);
        for (Card card : buildDeck()) {
            ids.add(card.cardId());
        }
        fisherYates(ids, new SplittableRandom(seed));

        List<List<Integer>> hands = new ArrayList<>(PLAYER_COUNT);
        for (int player = 0; player < PLAYER_COUNT; player++) {
            int from = player * HAND_SIZE;
            hands.add(List.copyOf(ids.subList(from, from + HAND_SIZE)));
        }
        return List.copyOf(hands);
    }

    private static void fisherYates(List<Integer> ids, SplittableRandom rng) {
        for (int i = ids.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = ids.get(i);
            ids.set(i, ids.get(j));
            ids.set(j, tmp);
        }
    }
}
