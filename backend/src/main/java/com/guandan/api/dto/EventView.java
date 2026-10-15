package com.guandan.api.dto;

import java.util.Map;

/**
 * 公开事件视图：服务层分配的递增 seq、发生时的 state_version、事件类型、行为者与白名单 payload。
 * payload 由引擎产出，仅含公开信息（PLAYED 为本次 cards/声明/剩余张数等），非完整状态序列化。
 */
public record EventView(long seq, long stateVersion, String eventType, Integer actorId,
                        Map<String, Object> payload) {
}
