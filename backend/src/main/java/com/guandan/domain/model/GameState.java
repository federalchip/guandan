package com.guandan.domain.model;

import com.guandan.domain.RuleProfile;

import java.util.List;

/**
 * 完整牌局状态（含隐藏信息），只在引擎中纯转移。hands 为四家手牌（座位 0-3），
 * discarded 为已弃牌，currentPlayer 为当前玩家（FINISHED 时为 null），trick 为当前有效桌面牌
 * （领出时为 null），finishOrder 为实际出完次序，phase 阶段，result 结算结果（未结束时 null），
 * version 为状态版本号。所有嵌套集合在构造器中做防御性复制。
 */
public record GameState(RuleProfile profile,
                        List<List<Integer>> hands,
                        List<Integer> discarded,
                        Integer currentPlayer,
                        Trick trick,
                        List<Integer> finishOrder,
                        Phase phase,
                        GameResult result,
                        long version) {

    public GameState {
        hands = hands == null
                ? List.of()
                : hands.stream().map(List::copyOf).toList();
        discarded = discarded == null ? List.of() : List.copyOf(discarded);
        finishOrder = finishOrder == null ? List.of() : List.copyOf(finishOrder);
    }
}
