package com.guandan.rules;

import com.guandan.domain.Deck;
import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Card;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;
import com.guandan.domain.model.Suit;
import com.guandan.domain.model.WildBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 合法出牌候选生成器（生产路径）。按点数/花色建立手牌索引，按牌型模板与百搭缺口生成候选，
 * 规范化后按语义键去重，跟牌时用 {@link MoveComparator} 过滤；只产出 PLAY 候选（不含过牌）。
 *
 * <p>不枚举手牌的所有子集，也不把百搭无条件展开成 52×52；相同牌面副本取最低 CardId 作固定代表，
 * 不同花色/百搭消耗导致的剩余手牌差异作为不同候选保留。候选顺序稳定，不依赖哈希遍历次序。
 */
public final class LegalMoveGenerator {

    private static final Card[] CATALOG = buildCatalog();
    private static final int SMALL_JOKER = 15;
    private static final int BIG_JOKER = 16;
    private static final int MIN_RANK = 2;
    private static final int MAX_RANK = 14;
    private static final int SUIT_J = 4;

    private static Card[] buildCatalog() {
        Card[] a = new Card[108];
        for (Card c : Deck.buildDeck()) {
            a[c.cardId()] = c;
        }
        return a;
    }

    private LegalMoveGenerator() {
    }

    public static List<Move> generateLegalMoves(List<Integer> hand, Move previous, RuleProfile profile) {
        if (hand == null || hand.isEmpty()) {
            return List.of();
        }
        CardIdValidator.validate(hand);
        Generator g = new Generator(hand, profile);
        g.generate();
        return g.finish(previous);
    }

    /** 每次调用新建，持有该手牌的索引与去重状态。 */
    private static final class Generator {
        private final RuleProfile profile;
        private final int levelRank;
        private final List<Integer> wildIds = new ArrayList<>();
        private final int[][][] natIds = new int[17][5][];   // [rank][suit] -> 升序实体 id
        private final int[][] natCount = new int[17][5];
        private int wildCount;
        private int wildFaceId;

        @SuppressWarnings("unchecked")
        private final List<int[]>[][] comboCache = new ArrayList[17][11];

        private final Set<CandKey> seen = new HashSet<>();
        private final List<Move> moves = new ArrayList<>();

        Generator(List<Integer> hand, RuleProfile profile) {
            this.profile = profile;
            this.levelRank = profile.levelRank();
            for (int r = 0; r < 17; r++) {
                for (int s = 0; s < 5; s++) {
                    natIds[r][s] = new int[0];
                }
            }
            List<Integer> wilds = new ArrayList<>();
            List<List<Integer>> tmp = new ArrayList<>();
            for (int r = 0; r < 17; r++) {
                for (int s = 0; s < 5; s++) {
                    tmp.add(new ArrayList<>());
                }
            }
            List<Integer> sorted = new ArrayList<>(hand);
            Collections.sort(sorted);
            for (int id : sorted) {
                Card c = CATALOG[id];
                if (c.rank() == levelRank && c.suit() == Suit.H) {
                    wilds.add(id);
                } else {
                    tmp.get(c.rank() * 5 + suitIndex(c.suit())).add(id);
                }
            }
            wildIds.addAll(wilds);
            wildCount = wildIds.size();
            wildFaceId = wildCount > 0 ? wildIds.get(0) % 54 : (levelRank - 2) * 4 + 1;
            for (int r = 0; r < 17; r++) {
                for (int s = 0; s < 5; s++) {
                    List<Integer> l = tmp.get(r * 5 + s);
                    int[] arr = new int[l.size()];
                    for (int i = 0; i < l.size(); i++) {
                        arr[i] = l.get(i);
                    }
                    natIds[r][s] = arr;
                    natCount[r][s] = arr.length;
                }
            }
        }

        private static int suitIndex(Suit s) {
            return s.ordinal();
        }

        void generate() {
            genPureWild();
            genSingles();
            genPairs();
            genTriples();
            genBombs();
            genJokerBomb();
            genFullHouses();
            genStraights();
            genPairRuns();
            genTripleRuns();
        }

        List<Move> finish(Move previous) {
            List<Move> out = new ArrayList<>();
            for (Move m : moves) {
                if (previous == null || MoveComparator.canBeat(m, previous, profile)) {
                    out.add(m);
                }
            }
            out.sort((x, y) -> {
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
                return compareIntLists(x.cardIds(), y.cardIds());
            });
            return List.copyOf(out);
        }

        // ---- 各牌型 ----

        private void genPureWild() {
            if (wildCount >= 1) {
                emit(MoveKind.SINGLE, levelRank, List.of(), List.of(), 1, null);
            }
            if (wildCount >= 2) {
                emit(MoveKind.PAIR, levelRank, List.of(), List.of(), 2, null);
            }
        }

        private void genSingles() {
            for (int r = MIN_RANK; r <= BIG_JOKER; r++) {
                for (int s = 0; s < 5; s++) {
                    if (natCount[r][s] >= 1) {
                        emit(MoveKind.SINGLE, r, List.of(natIds[r][s][0]), List.of(), 0, null);
                    }
                }
            }
        }

        private void genPairs() {
            for (int r = MIN_RANK; r <= BIG_JOKER; r++) {
                for (int[] combo : combosOfRank(r, 2)) {
                    emit(MoveKind.PAIR, r, toList(combo), List.of(), 0, null);
                }
                if (r <= MAX_RANK && wildCount >= 1) {
                    for (int[] combo : combosOfRank(r, 1)) {
                        emit(MoveKind.PAIR, r, toList(combo), List.of(r), 1, null);
                    }
                }
            }
        }

        private void genTriples() {
            for (int r = MIN_RANK; r <= MAX_RANK; r++) {
                for (int[] combo : combosOfRank(r, 3)) {
                    emit(MoveKind.TRIPLE, r, toList(combo), List.of(), 0, null);
                }
                if (wildCount >= 1) {
                    for (int[] combo : combosOfRank(r, 2)) {
                        emit(MoveKind.TRIPLE, r, toList(combo), List.of(r), 1, null);
                    }
                }
                if (wildCount >= 2) {
                    for (int[] combo : combosOfRank(r, 1)) {
                        emit(MoveKind.TRIPLE, r, toList(combo), Arrays.asList(r, r), 2, null);
                    }
                }
            }
        }

        private void genBombs() {
            for (int r = MIN_RANK; r <= MAX_RANK; r++) {
                int available = totalNat(r) + wildCount;
                int maxK = Math.min(10, available);
                for (int k = 4; k <= maxK; k++) {
                    int maxW = Math.min(wildCount, k);
                    for (int w = 0; w <= maxW; w++) {
                        int natNeeded = k - w;
                        if (natNeeded <= 0) {
                            continue;
                        }
                        for (int[] combo : combosOfRank(r, natNeeded)) {
                            emit(MoveKind.BOMB, r, toList(combo), repeat(r, w), w, null);
                        }
                    }
                }
            }
        }

        private void genJokerBomb() {
            if (natCount[SMALL_JOKER][SUIT_J] >= 2 && natCount[BIG_JOKER][SUIT_J] >= 2) {
                List<Integer> ids = new ArrayList<>();
                ids.add(natIds[SMALL_JOKER][SUIT_J][0]);
                ids.add(natIds[SMALL_JOKER][SUIT_J][1]);
                ids.add(natIds[BIG_JOKER][SUIT_J][0]);
                ids.add(natIds[BIG_JOKER][SUIT_J][1]);
                emit(MoveKind.JOKER_BOMB, BIG_JOKER, ids, List.of(), 0, null);
            }
        }

        private void genFullHouses() {
            for (int a = MIN_RANK; a <= MAX_RANK; a++) {
                for (int ta = 1; ta <= 3; ta++) {
                    int wa = 3 - ta;
                    if (wa > wildCount) {
                        continue;
                    }
                    for (int[] comboA : combosOfRank(a, ta)) {
                        int remainingWild = wildCount - wa;
                        // 带牌对子：至少一张自然牌
                        for (int b = MIN_RANK; b <= BIG_JOKER; b++) {
                            if (b == a) {
                                continue;
                            }
                            boolean joker = b >= SMALL_JOKER;
                            int tbMin = joker ? 2 : 1;
                            for (int tb = tbMin; tb <= 2; tb++) {
                                int wb = 2 - tb;
                                if (wb > remainingWild) {
                                    continue;
                                }
                                for (int[] comboB : combosOfRank(b, tb)) {
                                    List<Integer> nat = new ArrayList<>(toList(comboA));
                                    nat.addAll(toList(comboB));
                                    List<Integer> subst = new ArrayList<>(repeat(a, wa));
                                    subst.addAll(repeat(b, wb));
                                    emit(MoveKind.FULL_HOUSE, a, nat, subst, wa + wb, null);
                                }
                            }
                        }
                        // 带牌对子：两张百搭（点数自由，取最小有效自然点数作规范展示）
                        if (remainingWild >= 2) {
                            int b = minRankExcluding(a);
                            List<Integer> subst = new ArrayList<>(repeat(a, wa));
                            subst.addAll(repeat(b, 2));
                            emit(MoveKind.FULL_HOUSE, a, toList(comboA), subst, wa + 2, null);
                        }
                    }
                }
            }
        }

        private void genStraights() {
            for (int low = 1; low <= 10; low++) {
                genRun(MoveKind.STRAIGHT, low, 5, 1, -1);
                for (int s = 0; s < 4; s++) {
                    genRun(MoveKind.STRAIGHT_FLUSH, low, 5, 1, s);
                }
            }
        }

        private void genPairRuns() {
            for (int low = 1; low <= 12; low++) {
                genRun(MoveKind.PAIR_RUN, low, 3, 2, -1);
            }
        }

        private void genTripleRuns() {
            for (int low = 1; low <= 13; low++) {
                genRun(MoveKind.TRIPLE_RUN, low, 2, 3, -1);
            }
        }

        /** 序列牌型窗口枚举；flushSuit>=0 时为同花顺，自然牌限定该花色、百搭补该花色。 */
        private void genRun(MoveKind kind, int low, int numValues, int perValue, int flushSuit) {
            int[] ranks = new int[numValues];
            for (int i = 0; i < numValues; i++) {
                int value = low + i;
                ranks[i] = value == 1 ? MAX_RANK : value;
            }
            int high = low + numValues - 1;
            runRecurse(kind, ranks, perValue, high, flushSuit, 0, 0, new ArrayList<>(), new ArrayList<>());
        }

        private void runRecurse(MoveKind kind, int[] ranks, int perValue, int high, int flushSuit,
                                int pos, int wildsUsed, List<Integer> natAcc, List<Integer> wildAcc) {
            if (pos == ranks.length) {
                Suit fs = flushSuit >= 0 ? Suit.values()[flushSuit] : null;
                emit(kind, high, new ArrayList<>(natAcc), new ArrayList<>(wildAcc), wildsUsed, fs);
                return;
            }
            int r = ranks[pos];
            int maxW = Math.min(perValue, wildCount - wildsUsed);
            for (int wi = 0; wi <= maxW; wi++) {
                int natNeeded = perValue - wi;
                List<int[]> combos = flushSuit >= 0
                        ? combosOfRankSuit(r, natNeeded, flushSuit)
                        : combosOfRank(r, natNeeded);
                for (int[] combo : combos) {
                    int baseNat = natAcc.size();
                    int baseWild = wildAcc.size();
                    for (int id : combo) {
                        natAcc.add(id);
                    }
                    for (int j = 0; j < wi; j++) {
                        wildAcc.add(r);
                    }
                    runRecurse(kind, ranks, perValue, high, flushSuit, pos + 1, wildsUsed + wi, natAcc, wildAcc);
                    while (natAcc.size() > baseNat) {
                        natAcc.remove(natAcc.size() - 1);
                    }
                    while (wildAcc.size() > baseWild) {
                        wildAcc.remove(wildAcc.size() - 1);
                    }
                }
            }
        }

        // ---- 组合枚举 ----

        private List<int[]> combosOfRank(int rank, int k) {
            if (k == 0) {
                return List.of(new int[0]);
            }
            if (k < 0 || k > 10) {
                return List.of();
            }
            List<int[]> cached = comboCache[rank][k];
            if (cached != null) {
                return cached;
            }
            List<int[]> out = new ArrayList<>();
            combosRec(rank, k, 0, new int[5], out);
            comboCache[rank][k] = out;
            return out;
        }

        private void combosRec(int rank, int remaining, int suit, int[] pick, List<int[]> out) {
            if (suit == 5) {
                if (remaining == 0) {
                    out.add(materialize(rank, pick));
                }
                return;
            }
            int max = Math.min(natCount[rank][suit], remaining);
            for (int take = 0; take <= max; take++) {
                pick[suit] = take;
                combosRec(rank, remaining - take, suit + 1, pick, out);
            }
            pick[suit] = 0;
        }

        private int[] materialize(int rank, int[] pick) {
            int total = 0;
            for (int s = 0; s < 5; s++) {
                total += pick[s];
            }
            int[] ids = new int[total];
            int idx = 0;
            for (int s = 0; s < 5; s++) {
                for (int j = 0; j < pick[s]; j++) {
                    ids[idx++] = natIds[rank][s][j];
                }
            }
            return ids;
        }

        private List<int[]> combosOfRankSuit(int rank, int k, int suit) {
            if (k == 0) {
                return List.of(new int[0]);
            }
            if (natCount[rank][suit] < k) {
                return List.of();
            }
            int[] ids = new int[k];
            for (int j = 0; j < k; j++) {
                ids[j] = natIds[rank][suit][j];
            }
            return List.of(ids);
        }

        // ---- 发射与去重 ----

        private void emit(MoveKind kind, int mainRank, List<Integer> natPick, List<Integer> wildSubst,
                          int wildsUsed, Suit flushSuit) {
            int length = natPick.size() + wildsUsed;
            if (length < 1 || length > 10) {
                return;
            }
            int[] faces = new int[length];
            int fi = 0;
            for (int id : natPick) {
                faces[fi++] = id % 54;
            }
            for (int i = 0; i < wildsUsed; i++) {
                faces[fi++] = wildFaceId;
            }
            Arrays.sort(faces);
            long packed = packFaces(faces);
            if (!seen.add(new CandKey(kind.ordinal(), length, mainRank, packed))) {
                return;
            }
            List<Integer> cardIds = new ArrayList<>(natPick);
            for (int i = 0; i < wildsUsed; i++) {
                cardIds.add(wildIds.get(i));
            }
            Collections.sort(cardIds);

            List<Integer> substSorted = new ArrayList<>(wildSubst);
            Collections.sort(substSorted);
            List<WildBinding> bindings = new ArrayList<>();
            for (int i = 0; i < substSorted.size(); i++) {
                bindings.add(new WildBinding(wildIds.get(i), substSorted.get(i), flushSuit));
            }
            moves.add(new Move(cardIds, kind, mainRank, length, bindings,
                    interpretationKey(kind, length, mainRank, cardIds)));
        }

        private static long packFaces(int[] sortedFaces) {
            long packed = 0;
            for (int i = 0; i < sortedFaces.length; i++) {
                packed |= ((long) sortedFaces[i]) << (6 * i);
            }
            return packed;
        }

        private String interpretationKey(MoveKind kind, int length, int mainRank, List<Integer> cardIds) {
            StringBuilder sb = new StringBuilder();
            sb.append(profile.profileId()).append('|').append(kind.name())
                    .append('|').append(length).append('|').append(mainRank);
            for (int id : cardIds) {
                sb.append('|').append(id);
            }
            return sb.toString();
        }

        // ---- 小工具 ----

        private int totalNat(int rank) {
            int t = 0;
            for (int s = 0; s < 5; s++) {
                t += natCount[rank][s];
            }
            return t;
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
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                l.add(rank);
            }
            return l;
        }

        private static List<Integer> toList(int[] arr) {
            List<Integer> l = new ArrayList<>(arr.length);
            for (int v : arr) {
                l.add(v);
            }
            return l;
        }

        private static int compareIntLists(List<Integer> a, List<Integer> b) {
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                int c = Integer.compare(a.get(i), b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(a.size(), b.size());
        }
    }

    /** 语义去重键：牌型 + 张数 + 主比较点数 + 消耗实体牌面多重集（faces 为 6bit/面 排序打包）。 */
    private record CandKey(int kindOrd, int length, int mainRank, long faces) {
    }
}
