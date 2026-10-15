package com.guandan.service;

import com.guandan.domain.model.GameState;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内游戏存储。ConcurrentHashMap 仅管理局记录与令牌索引；每局的读写一致性由记录自身的
 * {@link java.util.concurrent.locks.ReentrantLock} 保证。生命周期到进程退出：已结算或被放弃的局
 * 暂不回收；进程重启后统一清空，旧 game_id 走 404 恢复。不使用数据库或 @Transactional。
 */
@Component
public class InMemoryGameStore {

    private final ConcurrentHashMap<String, GameRecord> games = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenToGameId = new ConcurrentHashMap<>();

    GameRecord create(String gameId, String sessionToken, long gameSeed, GameState initialState) {
        GameRecord record = new GameRecord(gameId, sessionToken, gameSeed, initialState);
        games.put(gameId, record);
        tokenToGameId.put(sessionToken, gameId);
        return record;
    }

    GameRecord get(String gameId) {
        return gameId == null ? null : games.get(gameId);
    }

    /** 令牌所属的 game_id；用于区分无效令牌(401)与其他局的有效令牌(403)。 */
    String gameIdForToken(String sessionToken) {
        return sessionToken == null ? null : tokenToGameId.get(sessionToken);
    }

    int size() {
        return games.size();
    }
}
