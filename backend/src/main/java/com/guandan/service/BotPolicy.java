package com.guandan.service;

import com.guandan.domain.model.Action;
import com.guandan.domain.model.PlayerView;

import java.util.List;
import java.util.SplittableRandom;

/**
 * 机器人策略 seam：生产用 {@code RandomLegalPolicy::chooseAction}，测试可注入抛异常的替身验证整批回滚。
 * 策略只接收 PlayerView、合法动作与独立 RNG。
 */
@FunctionalInterface
public interface BotPolicy {
    Action chooseAction(PlayerView view, List<Action> legalActions, SplittableRandom rng);
}
