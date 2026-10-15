package com.guandan.api.dto;

/** 百搭绑定视图：{card_id, rank, suit}；仅同花顺 suit 为所声明花色，其他牌型为 null。 */
public record WildBindingView(int cardId, int rank, String suit) {
}
