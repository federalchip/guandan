package com.guandan.domain.model;

import java.util.Set;

/**
 * 当前一轮的有效桌面牌：ownerId 为当前有效出牌者，move 为桌面有效牌，
 * pendingResponders 为尚需响应的活跃玩家集合。无有效桌面牌时 GameState.trick 为 null。
 */
public record Trick(int ownerId, Move move, Set<Integer> pendingResponders) {

    public Trick {
        pendingResponders = pendingResponders == null ? Set.of() : Set.copyOf(pendingResponders);
    }
}
