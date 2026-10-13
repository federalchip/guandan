package com.guandan.domain.model;

import java.util.List;

/**
 * 公开桌面牌：只含已公开打出的实体牌 cards，供 PlayerView 使用，不暴露隐藏信息。
 */
public record PublicTrick(int ownerId, Move move, List<Card> cards) {

    public PublicTrick {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
