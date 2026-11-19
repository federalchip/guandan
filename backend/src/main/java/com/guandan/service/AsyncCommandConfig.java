package com.guandan.service;

/** 异步命令开关与时间预算；每局创建时固定 asyncEnabled，运行中改配置不影响已有牌局。 */
public record AsyncCommandConfig(boolean asyncEnabled, long batchTimeoutMillis, long pollAfterMillis) {
    public static AsyncCommandConfig syncDefaults() {
        return new AsyncCommandConfig(false, 12_000L, 500L);
    }
}
