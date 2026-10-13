package com.guandan.domain.model;

import java.util.List;

/**
 * 引擎一次纯转移的结果：新状态与本次产生的公开事件列表。
 */
public record EngineOutcome(GameState state, List<DomainEvent> events) {

    public EngineOutcome {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
