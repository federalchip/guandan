package com.guandan.domain.model;

import java.util.List;

/**
 * 玩家动作。PLAY 至少一张牌、可带声明键；PASS 不带牌与声明。
 * 用工厂方法 play/pass 构造，cardIds 做防御性复制。
 */
public record Action(ActionKind kind, List<Integer> cardIds, String interpretationKey) {

    public Action {
        cardIds = cardIds == null ? List.of() : List.copyOf(cardIds);
    }

    public static Action play(List<Integer> cardIds, String interpretationKey) {
        return new Action(ActionKind.PLAY, cardIds, interpretationKey);
    }

    public static Action pass() {
        return new Action(ActionKind.PASS, List.of(), null);
    }
}
