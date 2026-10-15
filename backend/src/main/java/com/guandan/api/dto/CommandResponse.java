package com.guandan.api.dto;

import java.util.List;

/**
 * 命令响应：request_id、accepted_version（真人单个动作后的 version，非机器人批次最终 version）、
 * state（批次提交后的 GameView）、events（本命令公开事件，每条带 seq 与 state_version）。
 */
public record CommandResponse(String requestId, long acceptedVersion, GameView state,
                              List<EventView> events) {
}
