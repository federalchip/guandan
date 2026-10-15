package com.guandan.api.dto;

import java.util.List;

/** 声明视图：对应领域 Move 的公开字段，enum 转字符串、List 转数组。 */
public record MoveView(List<Integer> cardIds,
                       String kind,
                       int mainRank,
                       int length,
                       List<WildBindingView> wildBindings,
                       String interpretationKey) {
}
