package com.guandan.domain;

import java.util.List;

/**
 * 固定规则版本与比较点数映射的载体。v1 固定为 gd-v1-level2：级牌 2、真人（座位 0）先出、
 * 出牌方向 0→1→2→3。turnOrder 做防御性复制。
 */
public record RuleProfile(String profileId, int levelRank, int firstPlayer, List<Integer> turnOrder) {

    public static final String V1_PROFILE_ID = "gd-v1-level2";
    public static final int V1_LEVEL_RANK = 2;
    public static final int V1_FIRST_PLAYER = 0;

    public RuleProfile {
        turnOrder = turnOrder == null ? List.of() : List.copyOf(turnOrder);
    }

    /** v1 固定规则：单局、两副牌 108 张、固定打 2、真人先出。 */
    public static RuleProfile v1() {
        return new RuleProfile(V1_PROFILE_ID, V1_LEVEL_RANK, V1_FIRST_PLAYER, List.of(0, 1, 2, 3));
    }
}
