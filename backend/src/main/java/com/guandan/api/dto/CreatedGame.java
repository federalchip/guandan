package com.guandan.api.dto;

/** 创建局响应：game_id、不可预测的临时 session_token、真人可见 state。两处 game_id 必须一致。 */
public record CreatedGame(String gameId, String sessionToken, GameView state) {
}
