package com.guandan.api.dto;

import java.util.List;

/**
 * 动作请求（带 kind 的判别联合）：PLAY 至少一张、最多十张、interpretation_key 可省略；
 * PASS 不得携带 card_ids 或 interpretation_key。额外字段由 Jackson fail-on-unknown 拒绝。
 * card_ids 为严格整数。判别联合校验在 {@link #validate()}（DTO 边界），不单独依赖 Bean Validation。
 */
public record ActionRequest(String kind, List<Integer> cardIds, String interpretationKey) {

    public static final int MIN_CARDS = 1;
    public static final int MAX_CARDS = 10;

    /** DTO 边界判别联合校验；不合法抛 {@link com.guandan.domain.model.GameRuleException}(SCHEMA_INVALID)。 */
    public void validate() {
        if (kind == null) {
            throw SchemaInvalid.of("action 缺少 kind");
        }
        switch (kind) {
            case "PASS" -> {
                if (cardIds != null || interpretationKey != null) {
                    throw SchemaInvalid.of("PASS 不得携带 card_ids 或 interpretation_key");
                }
            }
            case "PLAY" -> {
                if (cardIds == null || cardIds.size() < MIN_CARDS || cardIds.size() > MAX_CARDS) {
                    throw SchemaInvalid.of("PLAY 需携带 " + MIN_CARDS + "-" + MAX_CARDS + " 张 card_ids");
                }
                for (Integer id : cardIds) {
                    if (id == null) {
                        throw SchemaInvalid.of("card_ids 含 null");
                    }
                }
            }
            default -> throw SchemaInvalid.of("未知 action.kind: " + kind);
        }
    }
}
