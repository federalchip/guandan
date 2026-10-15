package com.guandan.bots;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 机器人 RNG 种子派生（合同 §5 步骤5）：把游戏 seed、当前 version、座位按固定大端字节序拼接，
 * 经 SHA-256 摘要后取前 8 字节为大端 long。确定性、可复现，不使用对象 hashCode、HashMap 遍历
 * 顺序或共享可变 RNG 状态；服务层与 M2 冒烟测试共用此派生，保证同一 JDK 17 实现结果一致。
 */
public final class BotSeeds {

    private static final String ALGORITHM = "SHA-256";

    private BotSeeds() {
    }

    public static long derive(long gameSeed, long version, int seat) {
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + Long.BYTES + Integer.BYTES);
        buf.putLong(gameSeed);
        buf.putLong(version);
        buf.putInt(seat);
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM).digest(buf.array());
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 应始终可用", e);
        }
    }
}
