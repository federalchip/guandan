package com.guandan.api.dto;

/** 公开卡牌视图：card_id/deck_index/rank/suit（S/H/D/C/J）。 */
public record CardView(int cardId, int deckIndex, int rank, String suit) {
}
