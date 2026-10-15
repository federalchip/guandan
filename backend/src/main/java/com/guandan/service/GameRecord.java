package com.guandan.service;

import com.guandan.domain.model.GameState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一局的可变记录：状态、公开事件、幂等账本、事件序号与每局 {@link ReentrantLock}。
 * 所有可变字段仅在持有 {@link #lock} 时读写；ConcurrentHashMap 只管理局记录，不代替整局锁。
 */
final class GameRecord {

    final String gameId;
    final String sessionToken;
    final long gameSeed;
    final ReentrantLock lock = new ReentrantLock();
    final List<SequencedEvent> events = new ArrayList<>();
    final Map<String, CachedCommand> idempotency = new HashMap<>();
    long nextSeq = 1;
    volatile GameState state;

    GameRecord(String gameId, String sessionToken, long gameSeed, GameState state) {
        this.gameId = gameId;
        this.sessionToken = sessionToken;
        this.gameSeed = gameSeed;
        this.state = state;
    }
}
