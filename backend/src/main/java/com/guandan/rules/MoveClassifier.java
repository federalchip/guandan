package com.guandan.rules;

import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Card;
import com.guandan.domain.model.GameRuleException;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import com.guandan.domain.model.Suit;
import com.guandan.domain.model.WildBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 选牌解析：对一批实体牌返回所有在牌型/比较点数上不同的有效声明。纯 Java，不依赖 Spring/Jackson。
 *
 * <p>规则依据 docs/rules-v1.md：百搭为红桃级牌（v1 即红桃 2），可替代任意普通点数及同花顺花色，
 * 不能替代大小王；纯百搭选牌锁定为级牌单张/对子。语义声明按 (kind, length, mainRank) 归并，
 * 展示绑定取规范代表（替代点数升序 zip 百搭实体 ID 升序）。mainRank 存自然点数，级牌提升由
 * {@link MoveComparator} 负责。
 */
public final class MoveClassifier {

    private static final Map<Integer, Card> CARD_BY_ID;

    static {
        Map<Integer, Card> map = new HashMap<>();
        for (Card c : Deck.buildDeck()) {
            map.put(c.cardId(), c);
        }
        CARD_BY_ID = Map.copyOf(map);
    }

    private static final int MIN_CARD_ID = 0;
    private static final int MAX_CARD_ID = 107;
    private static final int SMALL_JOKER = 15;
    private static final int BIG_JOKER = 16;
    private static final int MIN_RANK = 2;
    private static final int MAX_RANK = 14;
    private static final int ACE_LOW_VALUE = 1;

    private MoveClassifier() {
    }

    /** 返回所选牌的全部有效声明；无有效牌型返回空列表；越界/重复 ID 抛 {@link GameRuleException}。 */
    public static List<Move> classify(List<Integer> cardIds, RuleProfile profile) {
        if (cardIds == null) {
            return List.of();
        }
        validate(cardIds);
        int n = cardIds.size();
        if (n == 0 || n > 10) {
            return List.of();
        }

        List<Integer> sortedIds = new ArrayList<>(cardIds);
        Collections.sort(sortedIds);

        List<Card> naturals = new ArrayList<>();
        List<Integer> wildIds = new ArrayList<>();
        for (int id : sortedIds) {
            Card card = CARD_BY_ID.get(id);
            if (isWild(card, profile)) {
                wildIds.add(id);
            } else {
                naturals.add(card);
            }
        }
        int wilds = wildIds.size();

        List<Decl> decls = new ArrayList<>();
        if (naturals.isEmpty()) {
            // 纯百搭：锁定级牌，不产生替代绑定
            if (n == 1) {
                decls.add(new Decl(MoveKind.SINGLE, profile.levelRank(), List.of(), null));
            } else if (n == 2) {
                decls.add(new Decl(MoveKind.PAIR, profile.levelRank(), List.of(), null));
            }
        } else {
            switch (n) {
                case 1 -> decls.addAll(singles(naturals));
                case 2 -> decls.addAll(pairs(naturals, wilds));
                case 3 -> decls.addAll(triples(naturals, wilds));
                case 4 -> {
                    decls.addAll(bombs(naturals, wilds));
                    decls.addAll(jokerBombs(naturals, wilds));
                }
                case 5 -> {
                    decls.addAll(fullHouses(naturals, wilds, profile));
                    decls.addAll(straights(naturals, wilds, false));
                    decls.addAll(straights(naturals, wilds, true));
                    decls.addAll(bombs(naturals, wilds));
                }
                case 6 -> {
                    decls.addAll(pairRuns(naturals, wilds));
                    decls.addAll(tripleRuns(naturals, wilds));
                    decls.addAll(bombs(naturals, wilds));
                }
                default -> decls.addAll(bombs(naturals, wilds)); // 7..10
            }
        }
        return toMoves(decls, sortedIds, n, wildIds, profile);
    }

    private static void validate(List<Integer> cardIds) {
        Set<Integer> seen = new HashSet<>();
        for (int id : cardIds) {
            if (id < MIN_CARD_ID || id > MAX_CARD_ID) {
                throw new GameRuleException("CARD_ID_OUT_OF_RANGE", "card_id 越界: " + id,
                        Map.of("card_id", id));
            }
            if (!seen.add(id)) {
                throw new GameRuleException("DUPLICATE_CARD_ID", "重复 card_id: " + id,
                        Map.of("card_id", id));
            }
        }
    }

    private static boolean isWild(Card card, RuleProfile profile) {
        return card.rank() == profile.levelRank() && card.suit() == Suit.H;
    }

    private static List<Decl> singles(List<Card> naturals) {
        return List.of(new Decl(MoveKind.SINGLE, naturals.get(0).rank(), List.of(), null));
    }

    private static List<Decl> pairs(List<Card> naturals, int wilds) {
        if (wilds == 0) {
            Card a = naturals.get(0);
            Card b = naturals.get(1);
            if (a.rank() == b.rank()) {
                return List.of(new Decl(MoveKind.PAIR, a.rank(), List.of(), null));
            }
            return List.of();
        }
        // wilds == 1（wilds==2 属纯百搭，已在上层处理）
        Card x = naturals.get(0);
        if (x.rank() >= SMALL_JOKER) {
            return List.of(); // 百搭不能替代王
        }
        return List.of(new Decl(MoveKind.PAIR, x.rank(), repeat(x.rank(), 1), null));
    }

    private static List<Decl> triples(List<Card> naturals, int wilds) {
        int r = naturals.get(0).rank();
        if (r >= SMALL_JOKER) {
            return List.of();
        }
        for (Card c : naturals) {
            if (c.rank() != r) {
                return List.of();
            }
        }
        return List.of(new Decl(MoveKind.TRIPLE, r, repeat(r, wilds), null));
    }

    private static List<Decl> bombs(List<Card> naturals, int wilds) {
        int r = naturals.get(0).rank();
        if (r >= SMALL_JOKER) {
            return List.of();
        }
        for (Card c : naturals) {
            if (c.rank() != r) {
                return List.of();
            }
        }
        return List.of(new Decl(MoveKind.BOMB, r, repeat(r, wilds), null));
    }

    private static List<Decl> jokerBombs(List<Card> naturals, int wilds) {
        if (wilds != 0 || naturals.size() != 4) {
            return List.of();
        }
        int small = 0;
        int big = 0;
        for (Card c : naturals) {
            if (c.rank() == SMALL_JOKER) {
                small++;
            } else if (c.rank() == BIG_JOKER) {
                big++;
            } else {
                return List.of();
            }
        }
        if (small == 2 && big == 2) {
            return List.of(new Decl(MoveKind.JOKER_BOMB, BIG_JOKER, List.of(), null));
        }
        return List.of();
    }

    private static List<Decl> fullHouses(List<Card> naturals, int wilds, RuleProfile profile) {
        List<Decl> out = new ArrayList<>();
        List<Card> jokers = new ArrayList<>();
        List<Card> normals = new ArrayList<>();
        for (Card c : naturals) {
            if (c.rank() >= SMALL_JOKER) {
                jokers.add(c);
            } else {
                normals.add(c);
            }
        }

        // 情形 A：真实王对子作带牌，其余（普通自然牌 + 百搭）组成三张
        if (jokers.size() == 2 && jokers.get(0).rank() == jokers.get(1).rank()
                && !normals.isEmpty() && normals.size() + wilds == 3) {
            int a = normals.get(0).rank();
            boolean same = true;
            for (Card c : normals) {
                if (c.rank() != a) {
                    same = false;
                    break;
                }
            }
            if (same) {
                out.add(new Decl(MoveKind.FULL_HOUSE, a, sorted(repeat(a, wilds)), null));
            }
        }

        // 情形 B：无王，全部普通牌组成 三张A + 对子B（A≠B）
        if (jokers.isEmpty()) {
            Map<Integer, Integer> counts = new TreeMap<>();
            for (Card c : normals) {
                counts.merge(c.rank(), 1, Integer::sum);
            }
            List<Integer> ranks = new ArrayList<>(counts.keySet());
            if (ranks.size() == 1) {
                int r = ranks.get(0);
                if (counts.get(r) == 3 && wilds == 2) {
                    int b = minRankExcluding(r);
                    out.add(new Decl(MoveKind.FULL_HOUSE, r, sorted(List.of(b, b)), null));
                }
            } else if (ranks.size() == 2) {
                int r1 = ranks.get(0);
                int r2 = ranks.get(1);
                int c1 = counts.get(r1);
                int c2 = counts.get(r2);
                if (c1 <= 3 && c2 <= 2) {
                    List<Integer> wr = new ArrayList<>(repeat(r1, 3 - c1));
                    wr.addAll(repeat(r2, 2 - c2));
                    out.add(new Decl(MoveKind.FULL_HOUSE, r1, sorted(wr), null));
                }
                if (c2 <= 3 && c1 <= 2) {
                    List<Integer> wr = new ArrayList<>(repeat(r2, 3 - c2));
                    wr.addAll(repeat(r1, 2 - c1));
                    out.add(new Decl(MoveKind.FULL_HOUSE, r2, sorted(wr), null));
                }
            }
        }
        return out;
    }

    private static List<Decl> straights(List<Card> naturals, int wilds, boolean flush) {
        if (naturals.isEmpty()) {
            return List.of();
        }
        for (Card c : naturals) {
            if (c.rank() >= SMALL_JOKER) {
                return List.of(); // 王不能参与顺子
            }
        }
        Suit flushSuit = null;
        if (flush) {
            flushSuit = naturals.get(0).suit();
            if (flushSuit == Suit.J) {
                return List.of();
            }
            for (Card c : naturals) {
                if (c.suit() != flushSuit) {
                    return List.of();
                }
            }
        }
        List<Decl> out = new ArrayList<>();
        for (int low = 1; low <= 10; low++) {
            Map<Integer, Integer> valueCount = new HashMap<>();
            boolean ok = true;
            for (Card c : naturals) {
                Integer val = valueInWindow(c.rank(), low, 4);
                if (val == null) {
                    ok = false;
                    break;
                }
                if (valueCount.merge(val, 1, Integer::sum) > 1) {
                    ok = false; // 顺子要求点数互不相同
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            List<Integer> wildRanks = new ArrayList<>();
            for (int val = low; val <= low + 4; val++) {
                if (valueCount.getOrDefault(val, 0) == 0) {
                    wildRanks.add(rankOfValue(val));
                }
            }
            if (wildRanks.size() != wilds) {
                continue;
            }
            out.add(new Decl(flush ? MoveKind.STRAIGHT_FLUSH : MoveKind.STRAIGHT,
                    low + 4, sorted(wildRanks), flushSuit));
        }
        return out;
    }

    private static List<Decl> pairRuns(List<Card> naturals, int wilds) {
        return runs(naturals, wilds, 2, 2, 12, MoveKind.PAIR_RUN);
    }

    private static List<Decl> tripleRuns(List<Card> naturals, int wilds) {
        return runs(naturals, wilds, 1, 3, 13, MoveKind.TRIPLE_RUN);
    }

    /**
     * 连对/钢板通用识别：窗口 [low, low+span]，每个点数需 exactly perValue 张。
     * maxLow 为窗口下界上限（保证高端点不超过 A）。
     */
    private static List<Decl> runs(List<Card> naturals, int wilds, int span, int perValue,
                                   int maxLow, MoveKind kind) {
        if (naturals.isEmpty()) {
            return List.of();
        }
        for (Card c : naturals) {
            if (c.rank() >= SMALL_JOKER) {
                return List.of();
            }
        }
        List<Decl> out = new ArrayList<>();
        for (int low = 1; low <= maxLow; low++) {
            Map<Integer, Integer> valueCount = new HashMap<>();
            boolean ok = true;
            for (Card c : naturals) {
                Integer val = valueInWindow(c.rank(), low, span);
                if (val == null) {
                    ok = false;
                    break;
                }
                if (valueCount.merge(val, 1, Integer::sum) > perValue) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            List<Integer> wildRanks = new ArrayList<>();
            for (int val = low; val <= low + span; val++) {
                int need = perValue - valueCount.getOrDefault(val, 0);
                wildRanks.addAll(repeat(rankOfValue(val), need));
            }
            if (wildRanks.size() != wilds) {
                continue;
            }
            out.add(new Decl(kind, low + span, sorted(wildRanks), null));
        }
        return out;
    }

    private static List<Move> toMoves(List<Decl> decls, List<Integer> sortedIds, int length,
                                      List<Integer> wildIds, RuleProfile profile) {
        List<Move> moves = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Decl d : decls) {
            String dedup = d.kind().name() + "/" + length + "/" + d.mainRank();
            if (!seen.add(dedup)) {
                continue;
            }
            List<WildBinding> bindings = new ArrayList<>();
            List<Integer> ranks = d.wildRanks();
            for (int i = 0; i < ranks.size(); i++) {
                bindings.add(new WildBinding(wildIds.get(i), ranks.get(i), d.flushSuit()));
            }
            String key = interpretationKey(profile, d.kind(), length, d.mainRank(), sortedIds);
            moves.add(new Move(sortedIds, d.kind(), d.mainRank(), length, bindings, key));
        }
        moves.sort((a, b) -> a.kind() != b.kind()
                ? Integer.compare(a.kind().ordinal(), b.kind().ordinal())
                : Integer.compare(a.mainRank(), b.mainRank()));
        return List.copyOf(moves);
    }

    private static String interpretationKey(RuleProfile profile, MoveKind kind, int length,
                                            int mainRank, List<Integer> sortedIds) {
        StringBuilder sb = new StringBuilder();
        sb.append(profile.profileId()).append('|').append(kind.name())
                .append('|').append(length).append('|').append(mainRank);
        for (int id : sortedIds) {
            sb.append('|').append(id);
        }
        return sb.toString();
    }

    /** 点数在窗口 [low, low+span] 中对应的自然序列值；A 可作 1(低)或 14(高)；王返回 null。 */
    private static Integer valueInWindow(int rank, int low, int span) {
        int high = low + span;
        if (rank >= SMALL_JOKER) {
            return null;
        }
        if (rank == MAX_RANK) { // Ace
            if (low <= MAX_RANK && MAX_RANK <= high) {
                return MAX_RANK;
            }
            if (low <= ACE_LOW_VALUE && ACE_LOW_VALUE <= high) {
                return ACE_LOW_VALUE;
            }
            return null;
        }
        if (low <= rank && rank <= high) {
            return rank;
        }
        return null;
    }

    private static int rankOfValue(int value) {
        return value == ACE_LOW_VALUE ? MAX_RANK : value;
    }

    private static int minRankExcluding(int rank) {
        for (int x = MIN_RANK; x <= MAX_RANK; x++) {
            if (x != rank) {
                return x;
            }
        }
        return MIN_RANK;
    }

    private static List<Integer> repeat(int rank, int count) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(rank);
        }
        return list;
    }

    private static List<Integer> sorted(List<Integer> ranks) {
        List<Integer> copy = new ArrayList<>(ranks);
        Collections.sort(copy);
        return copy;
    }

    /** 内部声明：牌型、主比较点数、百搭替代点数（升序，size==所用百搭数）、同花顺花色（其余为 null）。 */
    private record Decl(MoveKind kind, int mainRank, List<Integer> wildRanks, Suit flushSuit) {
    }
}
