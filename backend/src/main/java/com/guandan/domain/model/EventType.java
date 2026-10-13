package com.guandan.domain.model;

/**
 * 领域事件类型。payload 只含公开信息。
 */
public enum EventType {
    PLAYED,
    PASSED,
    TRICK_CLOSED,
    PLAYER_FINISHED,
    GAME_FINISHED
}
