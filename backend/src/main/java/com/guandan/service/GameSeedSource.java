package com.guandan.service;

/**
 * 游戏 seed 来源 seam：生产用 {@link java.security.SecureRandom}（不返回浏览器），测试注入固定 seed 复现。
 */
@FunctionalInterface
public interface GameSeedSource {
    long nextSeed();
}
