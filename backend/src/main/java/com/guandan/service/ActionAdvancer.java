package com.guandan.service;

import com.guandan.domain.model.Action;
import com.guandan.domain.model.EngineOutcome;
import com.guandan.domain.model.GameState;

/**
 * 动作推进 seam：生产用 {@code GameEngine::applyAction}，测试可注入“不推进回合”的替身触发 512 步保护。
 */
@FunctionalInterface
public interface ActionAdvancer {
    EngineOutcome applyAction(GameState state, int playerId, Action action);
}
