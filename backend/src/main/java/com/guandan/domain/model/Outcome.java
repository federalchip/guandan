package com.guandan.domain.model;

/**
 * 结算结果类型。DOUBLE_UP=双下（同队包揽头游二游）；TEAMMATE_THIRD=头游搭档第三；
 * TEAMMATE_LAST=头游搭档末游。
 */
public enum Outcome {
    DOUBLE_UP,
    TEAMMATE_THIRD,
    TEAMMATE_LAST
}
