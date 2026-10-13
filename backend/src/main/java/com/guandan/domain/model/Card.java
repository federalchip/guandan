package com.guandan.domain.model;

/**
 * 一张实体牌。cardId 为 0-107 的唯一实体 ID；deckIndex 为 0/1（两副）；
 * rank 普通牌 2-14、15=小王、16=大王；suit 为 S/H/D/C/J。
 * ID 公式：普通牌 deckIndex*54+(rank-2)*4+suitIndex，每副 52/53 为小/大王。
 */
public record Card(int cardId, int deckIndex, int rank, Suit suit) {
}
