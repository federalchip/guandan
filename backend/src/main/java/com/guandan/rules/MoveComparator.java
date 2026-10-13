package com.guandan.rules;

import com.guandan.domain.RuleProfile;
import com.guandan.domain.model.Move;
import com.guandan.domain.model.MoveKind;

/**
 * 牌型压制比较。纯 Java，不依赖 Spring/Jackson。
 *
 * <p>普通牌型必须同牌型、同张数才可比，主比较点数大者胜（级牌在单张/对子/三张/三带对/炸弹中提升，
 * 高于 A、低于小王；序列牌型按自然高端点，不提升）。炸弹体系按层级：天王炸弹 &gt; 10~6 张炸弹
 * &gt; 同花顺 &gt; 5 张炸弹 &gt; 4 张炸弹；张数优先于点数，同张数普通炸弹按提升后点数比较，
 * 同花顺按自然高端点比较（花色不分大小）。不使用统一排序值跨型压制。
 */
public final class MoveComparator {

    private static final int TIER_JOKER_BOMB = 12;
    private static final int TIER_STRAIGHT_FLUSH = 6;
    private static final double RAISED_LEVEL_VALUE = 14.5; // 高于 A(14)、低于小王(15)

    private MoveComparator() {
    }

    /** challenger 是否能压过 previous；previous 为 null（领出）时任何出牌均可，返回 true。 */
    public static boolean canBeat(Move challenger, Move previous, RuleProfile profile) {
        if (previous == null) {
            return true;
        }
        boolean challengerBomb = isBombSystem(challenger.kind());
        boolean previousBomb = isBombSystem(previous.kind());
        if (challengerBomb && !previousBomb) {
            return true;
        }
        if (!challengerBomb && previousBomb) {
            return false;
        }
        if (challengerBomb) {
            int challengerTier = bombTier(challenger);
            int previousTier = bombTier(previous);
            if (challengerTier != previousTier) {
                return challengerTier > previousTier;
            }
            if (challenger.kind() == MoveKind.JOKER_BOMB) {
                return false; // 唯一最大，相等不能压
            }
            if (challenger.kind() == MoveKind.STRAIGHT_FLUSH) {
                return challenger.mainRank() > previous.mainRank(); // 自然高端点，花色不分大小
            }
            return compareValue(challenger, profile) > compareValue(previous, profile); // 同张数普通炸弹
        }
        // 双方均为普通牌型
        if (challenger.kind() != previous.kind()) {
            return false;
        }
        if (challenger.length() != previous.length()) {
            return false;
        }
        return compareValue(challenger, profile) > compareValue(previous, profile);
    }

    private static boolean isBombSystem(MoveKind kind) {
        return kind == MoveKind.BOMB || kind == MoveKind.STRAIGHT_FLUSH || kind == MoveKind.JOKER_BOMB;
    }

    private static int bombTier(Move move) {
        return switch (move.kind()) {
            case JOKER_BOMB -> TIER_JOKER_BOMB;
            case STRAIGHT_FLUSH -> TIER_STRAIGHT_FLUSH;
            // 4→4, 5→5, 6→7, 7→8, 8→9, 9→10, 10→11（6 张及以上越过同花顺层）
            case BOMB -> move.length() <= 5 ? move.length() : move.length() + 1;
            default -> -1;
        };
    }

    private static double compareValue(Move move, RuleProfile profile) {
        boolean sequence = move.kind() == MoveKind.STRAIGHT
                || move.kind() == MoveKind.PAIR_RUN
                || move.kind() == MoveKind.TRIPLE_RUN
                || move.kind() == MoveKind.STRAIGHT_FLUSH;
        if (!sequence && move.mainRank() == profile.levelRank()) {
            return RAISED_LEVEL_VALUE;
        }
        return move.mainRank();
    }
}
