package com.guandan.domain.model;

import java.util.List;

/**
 * 一次出牌的一个有效声明。cardIds 为消耗的实体牌；kind 牌型；mainRank 主比较点数
 * （三带对为三张点数，连牌为规范化高端点，存自然点数，级牌提升由 MoveComparator 负责）；
 * length 张数；wildBindings 规范展示绑定；interpretationKey 为服务端生成的稳定声明键。
 * 嵌套集合在构造器中做防御性复制。
 */
public record Move(List<Integer> cardIds,
                   MoveKind kind,
                   int mainRank,
                   int length,
                   List<WildBinding> wildBindings,
                   String interpretationKey) {

    public Move {
        cardIds = cardIds == null ? List.of() : List.copyOf(cardIds);
        wildBindings = wildBindings == null ? List.of() : List.copyOf(wildBindings);
    }
}
