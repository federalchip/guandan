package com.guandan.domain.model;

import java.util.List;

/**
 * 面向单个玩家的公开视图，不含其他人的手牌和服务端种子。ownHand 为本人手牌实体，
 * remainingCounts 为四家剩余张数，trick 为公开桌面牌，finishOrder 出完次序，
 * currentPlayer 当前玩家，isLead 是否领出，canPass 是否允许过牌，profileId/levelRank 规则标识，
 * phase 阶段，result 结算结果，version 版本号。嵌套集合做防御性复制。
 */
public record PlayerView(List<Card> ownHand,
                         List<Integer> remainingCounts,
                         PublicTrick trick,
                         List<Integer> finishOrder,
                         Integer currentPlayer,
                         boolean isLead,
                         boolean canPass,
                         String profileId,
                         int levelRank,
                         Phase phase,
                         GameResult result,
                         long version) {

    public PlayerView {
        ownHand = ownHand == null ? List.of() : List.copyOf(ownHand);
        remainingCounts = remainingCounts == null ? List.of() : List.copyOf(remainingCounts);
        finishOrder = finishOrder == null ? List.of() : List.copyOf(finishOrder);
    }
}
