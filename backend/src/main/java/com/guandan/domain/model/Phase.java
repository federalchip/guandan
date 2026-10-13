package com.guandan.domain.model;

/**
 * 牌局阶段。PLAYING 进行中；FINISHED 已结算，此时 currentPlayer 为 null。
 */
public enum Phase {
    PLAYING,
    FINISHED
}
