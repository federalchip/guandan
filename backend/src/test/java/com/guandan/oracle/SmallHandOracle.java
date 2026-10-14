package com.guandan.oracle;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 测试专用的独立慢速参照（T4a）。仅用于验证，绝不参与生产路径。
 *
 * <p>设计原则：用与生产实现不同的、可直接审计的暴力算法独立推导预期——
 * <ul>
 *   <li>{@link #classify} 枚举百搭（红桃级牌）在 2..14 上的全部赋秩组合（对每张百搭至多 13 种，
 *       不展开花色，避免 52×52 爆炸），对每个组合按点数多重集从第一性原理判断牌型；
 *       Ace 的高/低解释用位掩码穷举；再按 (牌型, 长度, 主点) 归并，展示绑定取代位秩字典序最小者。</li>
 *   <li>{@link #generateMoves} 穷举手牌（≤10 张）的全部非空子集，逐子集判定并按独立的可压制关系过滤，
 *       最后按 (消耗牌面多重集, 牌型, 长度, 主点) 归并等价副本。</li>
 * </ul>
 *
 * <p>允许共用牌目录 {@link Deck} 与领域类型；不调用任何生产判定/比较/生成函数得到参照预期。
 */
public final class SmallHandOracle {

    private static final Map<Integer, Card> CATALOG;

    static {
        Map<Integer, Card> map = new HashMap<>();
        for (Card c : Deck.buildDeck()) {
            map.put(c.cardId(), c);
        }
        CATALOG = Map.copyOf(map);
    }

    private static final int MIN_CARD_ID = 0;
    private static final int MAX_CARD_ID = 107;
    private static final int SMALL_JOKER = 15;
    private static final int BIG_JOKER = 16;
    private static final int MIN_RANK = 2;
    private static final int MAX_RANK = 14;
    private static final int ACE_LOW = 1;
    private static final int MAX_ORACLE_HAND = 10;

    private SmallHandOracle() {
    }

    /** 判断完整所选牌的全部有效声明（独立参照）。 */
    public static List<Move> classify(List<Integer> cardIds, RuleProfile profile) {
        if (cardIds == null) {
            return List.of();
        }
        validate(cardIds);
        int n = cardIds.size();
        if (n == 0 || n > MAX_ORACLE_HAND) {
            return List.of();
        }
        List<Integer> sortedIds = new ArrayList<>(cardIds);
        Collections.sort(sortedIds);

        List<Card> naturals = new ArrayList<>();
        List<Integer> wildIds = new ArrayList<>();
        for (int id : sortedIds) {
            Card card = CATALOG.get(id);
            if (isWild(card, profile)) {
                wildIds.add(id);
            } else {
                naturals.add(card);
            }
        }
        int wilds = wildIds.size();

        if (naturals.isEmpty()) {
            List<Move> pure = new ArrayList<>();
            if (n == 1) {
                pure.add(buildMove(sortedIds, MoveKind.SINGLE, profile.levelRank(), n, List.of(), profile));
            } else if (n == 2) {
                pure.add(buildMove(sortedIds, MoveKind.PAIR, profile.levelRank(), n, List.of(), profile));
            }
            return List.copyOf(pure);
        }

        boolean sameSuit = allSameNormalSuit(naturals);
        Suit flushSuit = sameSuit ? naturals.get(0).suit() : null;
        List<Integer> naturalRanks = new ArrayList<>();
        for (Card c : naturals) {
            naturalRanks.add(c.rank());
        }

        Map<String, Candidate> best = new LinkedHashMap<>();
        for (int[] assign : assignments(wilds)) {
            List<Integer> ranks = new ArrayList<>(naturalRanks);
            List<Integer> wildRanks = new ArrayList<>();
            for (int r : assign) {
                ranks.add(r);
                wildRanks.add(r);
            }
            Collections.sort(wildRanks);
            for (Found f : kindsForRanks(ranks, n, sameSuit, wilds)) {
                String key = f.kind().name() + "/" + n + "/" + f.mainRank();
                Candidate prev = best.get(key);
                if (prev == null || compareLists(wildRanks, prev.wildRanks()) < 0) {
                    Suit fs = f.kind() == MoveKind.STRAIGHT_FLUSH ? flushSuit : null;
                    best.put(key, new Candidate(f.kind(), f.mainRank(), wildRanks, fs));
                }
            }
        }

        List<Move> moves = new ArrayList<>();
        for (Candidate c : best.values()) {
            List<WildBinding> bindings = bindings(wildIds, c.wildRanks(), c.flushSuit());
            moves.add(buildMove(sortedIds, c.kind(), c.mainRank(), n, bindings, profile));
        }
        sortMoves(moves);
        return List.copyOf(moves);
    }

    /** 穷举手牌（≤10 张）的全部合法出牌候选；previous 非空时只保留能压过它的候选。 */
    public static List<Move> generateMoves(List<Integer> hand, Move previous, RuleProfile profile) {
        if (hand == null) {
            return List.of();
        }
        validate(hand);
        int n = hand.size();
        if (n > MAX_ORACLE_HAND) {
            throw new IllegalArgumentException("独立参照仅支持小手牌 (<=10)，实际: " + n);
        }
        List<Integer> sortedHand = new ArrayList<>(hand);
        Collections.sort(sortedHand);

        Map<String, Move> bySignature = new LinkedHashMap<>();
        int total = 1 << n;
        for (int mask = 1; mask < total; mask++) {
            List<Integer> subset = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(sortedHand.get(i));
                }
            }
            for (Move m : classify(subset, profile)) {
                if (previous != null && !beats(m, previous, profile)) {
                    continue;
                }
                bySignature.putIfAbsent(signature(m), m);
            }
        }
        List<Move> out = new ArrayList<>(bySignature.values());
        sortMoves(out);
        return List.copyOf(out);
    }

    // ---- 独立的可压制关系（不调用生产比较器）----

    private static boolean beats(Move challenger, Move previous, RuleProfile profile) {
        if (previous == null) {
            return true;
        }
        boolean cb = isBombFamily(challenger.kind());
        boolean pb = isBombFamily(previous.kind());
        if (cb && !pb) {
            return true;
        }
        if (!cb && pb) {
            return false;
        }
        if (cb) {
            int ct = tier(challenger);
            int pt = tier(previous);
            if (ct != pt) {
                return ct > pt;
            }
            if (challenger.kind() == MoveKind.JOKER_BOMB) {
                return false;
            }
            if (challenger.kind() == MoveKind.STRAIGHT_FLUSH) {
                return challenger.mainRank() > previous.mainRank();
            }
            return strength(challenger, profile) > strength(previous, profile);
        }
        if (challenger.kind() != previous.kind()) {
            return false;
        }
        if (challenger.length() != previous.length()) {
            return false;
        }
        return strength(challenger, profile) > strength(previous, profile);
    }

    private static boolean isBombFamily(MoveKind kind) {
        return kind == MoveKind.BOMB || kind == MoveKind.STRAIGHT_FLUSH || kind == MoveKind.JOKER_BOMB;
    }

    private static int tier(Move m) {
        return switch (m.kind()) {
            case JOKER_BOMB -> 12;
            case STRAIGHT_FLUSH -> 6;
            case BOMB -> m.length() <= 5 ? m.length() : m.length() + 1;
            default -> -1;
        };
    }

    private static double strength(Move m, RuleProfile profile) {
        boolean sequence = m.kind() == MoveKind.STRAIGHT
                || m.kind() == MoveKind.PAIR_RUN
                || m.kind() == MoveKind.TRIPLE_RUN
                || m.kind() == MoveKind.STRAIGHT_FLUSH;
        if (!sequence && m.mainRank() == profile.levelRank()) {
            return 14.5;
        }
        return m.mainRank();
    }

    // ---- 牌型判断（从点数多重集）----

    private static List<Found> kindsForRanks(List<Integer> ranks, int n, boolean sameSuit, int wilds) {
        List<Found> out = new ArrayList<>();
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int r : ranks) {
            counts.merge(r, 1, Integer::sum);
        }
        int distinct = counts.size();
        int maxRank = Collections.max(ranks);
        boolean hasJoker = maxRank >= SMALL_JOKER;
        switch (n) {
            case 1 -> out.add(new Found(MoveKind.SINGLE, ranks.get(0)));
            case 2 -> {
                if (distinct == 1) {
                    out.add(new Found(MoveKind.PAIR, maxRank));
                }
            }
            case 3 -> {
                if (distinct == 1 && maxRank <= MAX_RANK) {
                    out.add(new Found(MoveKind.TRIPLE, maxRank));
                }
            }
            case 4 -> {
                if (distinct == 1 && maxRank <= MAX_RANK) {
                    out.add(new Found(MoveKind.BOMB, maxRank));
                }
                if (wilds == 0 && distinct == 2
                        && counts.getOrDefault(SMALL_JOKER, 0) == 2
                        && counts.getOrDefault(BIG_JOKER, 0) == 2) {
                    out.add(new Found(MoveKind.JOKER_BOMB, BIG_JOKER));
                }
            }
            case 5 -> {
                if (distinct == 1 && maxRank <= MAX_RANK) {
                    out.add(new Found(MoveKind.BOMB, maxRank));
                }
                if (distinct == 2) {
                    int triple = -1;
                    int pair = -1;
                    for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                        if (e.getValue() == 3) {
                            triple = e.getKey();
                        } else if (e.getValue() == 2) {
                            pair = e.getKey();
                        }
                    }
                    if (triple > 0 && pair > 0 && triple != pair && triple <= MAX_RANK) {
                        out.add(new Found(MoveKind.FULL_HOUSE, triple));
                    }
                }
                if (!hasJoker) {
                    Integer high = matchRun(ranks, 5, 1);
                    if (high != null) {
                        out.add(new Found(MoveKind.STRAIGHT, high));
                        if (sameSuit) {
                            out.add(new Found(MoveKind.STRAIGHT_FLUSH, high));
                        }
                    }
                }
            }
            case 6 -> {
                if (distinct == 1 && maxRank <= MAX_RANK) {
                    out.add(new Found(MoveKind.BOMB, maxRank));
                }
                if (!hasJoker) {
                    Integer pairRunHigh = matchRun(ranks, 3, 2);
                    if (pairRunHigh != null) {
                        out.add(new Found(MoveKind.PAIR_RUN, pairRunHigh));
                    }
                    Integer tripleRunHigh = matchRun(ranks, 2, 3);
                    if (tripleRunHigh != null) {
                        out.add(new Found(MoveKind.TRIPLE_RUN, tripleRunHigh));
                    }
                }
            }
            default -> {
                if (distinct == 1 && maxRank <= MAX_RANK) {
                    out.add(new Found(MoveKind.BOMB, maxRank));
                }
            }
        }
        return out;
    }

    /**
     * 判断点数多重集是否构成 runLength 个连续点数、每点数 perValue 张的序列（顺子/连对/钢板）。
     * Ace(14) 可作低点 1 或高点 14，用位掩码穷举其解释；成立则返回高端点，否则 null。
     */
    private static Integer matchRun(List<Integer> ranks, int runLength, int perValue) {
        for (int r : ranks) {
            if (r >= SMALL_JOKER) {
                return null;
            }
        }
        if (ranks.size() != runLength * perValue) {
            return null;
        }
        List<Integer> acePositions = new ArrayList<>();
        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i) == MAX_RANK) {
                acePositions.add(i);
            }
        }
        int aceCount = acePositions.size();
        for (int mask = 0; mask < (1 << aceCount); mask++) {
            Map<Integer, Integer> valueCount = new HashMap<>();
            for (int i = 0; i < ranks.size(); i++) {
                int r = ranks.get(i);
                int value;
                if (r == MAX_RANK) {
                    int ordinal = acePositions.indexOf(i);
                    value = ((mask >> ordinal) & 1) == 0 ? ACE_LOW : MAX_RANK;
                } else {
                    value = r;
                }
                valueCount.merge(value, 1, Integer::sum);
            }
            if (valueCount.size() != runLength) {
                continue;
            }
            int min = Collections.min(valueCount.keySet());
            int max = Collections.max(valueCount.keySet());
            if (max - min != runLength - 1 || min < ACE_LOW || max > MAX_RANK) {
                continue;
            }
            boolean ok = true;
            for (int v = min; v <= max; v++) {
                if (valueCount.getOrDefault(v, 0) != perValue) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return max;
            }
        }
        return null;
    }

    // ---- 通用辅助 ----

    private static void validate(List<Integer> cardIds) {
        Set<Integer> seen = new HashSet<>();
        for (int id : cardIds) {
            if (id < MIN_CARD_ID || id > MAX_CARD_ID) {
                throw new GameRuleException("CARD_ID_OUT_OF_RANGE", "card_id 越界: " + id);
            }
            if (!seen.add(id)) {
                throw new GameRuleException("DUPLICATE_CARD_ID", "重复 card_id: " + id);
            }
        }
    }

    private static boolean isWild(Card card, RuleProfile profile) {
        return card.rank() == profile.levelRank() && card.suit() == Suit.H;
    }

    private static boolean allSameNormalSuit(List<Card> naturals) {
        Suit s = naturals.get(0).suit();
        if (s == Suit.J) {
            return false;
        }
        for (Card c : naturals) {
            if (c.suit() != s) {
                return false;
            }
        }
        return true;
    }

    private static List<int[]> assignments(int wilds) {
        List<int[]> out = new ArrayList<>();
        fillAssignments(new int[wilds], 0, wilds, out);
        return out;
    }

    private static void fillAssignments(int[] cur, int pos, int wilds, List<int[]> out) {
        if (pos == wilds) {
            out.add(cur.clone());
            return;
        }
        for (int r = MIN_RANK; r <= MAX_RANK; r++) {
            cur[pos] = r;
            fillAssignments(cur, pos + 1, wilds, out);
        }
    }

    private static List<WildBinding> bindings(List<Integer> wildIds, List<Integer> wildRanks, Suit flushSuit) {
        List<WildBinding> out = new ArrayList<>();
        for (int i = 0; i < wildRanks.size(); i++) {
            out.add(new WildBinding(wildIds.get(i), wildRanks.get(i), flushSuit));
        }
        return out;
    }

    private static Move buildMove(List<Integer> sortedIds, MoveKind kind, int mainRank, int length,
                                  List<WildBinding> bindings, RuleProfile profile) {
        StringBuilder sb = new StringBuilder("oracle|");
        sb.append(profile.profileId()).append('|').append(kind.name())
                .append('|').append(length).append('|').append(mainRank);
        for (int id : sortedIds) {
            sb.append('|').append(id);
        }
        return new Move(sortedIds, kind, mainRank, length, bindings, sb.toString());
    }

    private static String signature(Move m) {
        List<String> faces = new ArrayList<>();
        for (int id : m.cardIds()) {
            Card c = CATALOG.get(id);
            faces.add(c.rank() + "" + c.suit().name());
        }
        Collections.sort(faces);
        return m.kind().name() + "/" + m.length() + "/" + m.mainRank() + "#" + String.join(",", faces);
    }

    private static void sortMoves(List<Move> moves) {
        moves.sort((x, y) -> {
            int c = Integer.compare(x.kind().ordinal(), y.kind().ordinal());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(x.mainRank(), y.mainRank());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(x.length(), y.length());
            if (c != 0) {
                return c;
            }
            return compareLists(x.cardIds(), y.cardIds());
        });
    }

    private static int compareLists(List<Integer> a, List<Integer> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int cmp = Integer.compare(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private record Found(MoveKind kind, int mainRank) {
    }

    private record Candidate(MoveKind kind, int mainRank, List<Integer> wildRanks, Suit flushSuit) {
    }
}
