package com.guandan.domain.model;

import java.util.Map;

/**
 * 领域事件。eventType 事件类型，actorId 行为者座位（可为 null，如 GAME_FINISHED），
 * payload 只含公开信息。服务层另行分配递增 seq，不与 GameState.version 混用。
 */
public record DomainEvent(EventType eventType, Integer actorId, Map<String, Object> payload) {

    public DomainEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
