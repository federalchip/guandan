package com.guandan.rules;

import com.guandan.domain.model.GameRuleException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出牌 CardId 输入校验：选牌解析（{@link MoveClassifier}）与候选生成（{@link LegalMoveGenerator}）共用，
 * 保证两者对越界/重复实体抛出完全一致的稳定规则错误码（T7 统一映射 422 SCHEMA_INVALID）。
 */
final class CardIdValidator {

    static final int MIN_CARD_ID = 0;
    static final int MAX_CARD_ID = 107;

    private CardIdValidator() {
    }

    /** null 视为空输入；越界抛 CARD_ID_OUT_OF_RANGE，重复抛 DUPLICATE_CARD_ID。 */
    static void validate(List<Integer> cardIds) {
        if (cardIds == null) {
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (int id : cardIds) {
            if (id < MIN_CARD_ID || id > MAX_CARD_ID) {
                throw new GameRuleException("CARD_ID_OUT_OF_RANGE", "card_id 越界: " + id,
                        Map.of("card_id", id));
            }
            if (!seen.add(id)) {
                throw new GameRuleException("DUPLICATE_CARD_ID", "重复 card_id: " + id,
                        Map.of("card_id", id));
            }
        }
    }
}
