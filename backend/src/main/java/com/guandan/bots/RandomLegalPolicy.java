package com.guandan.bots;

import com.guandan.domain.model.Action;
import com.guandan.domain.model.PlayerView;

import java.util.List;
import java.util.SplittableRandom;

/**
 * 合法随机机器人：从供给的合法动作中均匀随机选择一个。纯 Java，不导入 service/store/engine，
 * 只接收 {@link PlayerView}、合法动作与独立 {@link SplittableRandom}，不接收其他人手牌或 GameState，
 * 无 LLM、无网络。候选顺序由供给方（引擎生成器）保证稳定，因此同一 seed 复现一致。
 *
 * <p>领出时供给的合法动作不含 PASS，机器人自然无法过牌；无可压动作时供给只含 PASS，机器人只能过牌。
 */
public final class RandomLegalPolicy {

    private RandomLegalPolicy() {
    }

    public static Action chooseAction(PlayerView view, List<Action> legalActions, SplittableRandom rng) {
        if (legalActions == null || legalActions.isEmpty()) {
            throw new IllegalArgumentException("机器人必须在非空合法动作中选择");
        }
        if (rng == null) {
            throw new IllegalArgumentException("rng 不能为空");
        }
        return legalActions.get(rng.nextInt(legalActions.size()));
    }
}
