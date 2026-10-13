package com.guandan.domain.model;

/**
 * 一条百搭（红桃级牌）绑定：cardId 为被解释的百搭实体 ID，rank 为其充当的点数，
 * suit 仅在同花顺声明中为所声明花色，其他牌型为 null。
 */
public record WildBinding(int cardId, int rank, Suit suit) {
}
