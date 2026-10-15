package com.guandan.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 面向真人（座位 0）的完整公开视图 = PlayerView 的 JSON 表示 + game_id。
 * 不含其他人手牌与服务端种子。isLead 显式映射为 is_lead。
 */
public record GameView(String gameId,
                       List<CardView> ownHand,
                       List<Integer> remainingCounts,
                       PublicTrickView trick,
                       List<Integer> finishOrder,
                       Integer currentPlayer,
                       @JsonProperty("is_lead") boolean isLead,
                       boolean canPass,
                       String profileId,
                       int levelRank,
                       String phase,
                       ResultView result,
                       long version) {
}
