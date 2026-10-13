package com.guandan.domain.model;

/**
 * 牌型。普通牌型与炸弹体系牌型；比较规则见 docs/rules-v1.md 第 5 节。
 */
public enum MoveKind {
    SINGLE,
    PAIR,
    TRIPLE,
    FULL_HOUSE,
    STRAIGHT,
    PAIR_RUN,
    TRIPLE_RUN,
    BOMB,
    STRAIGHT_FLUSH,
    JOKER_BOMB
}
