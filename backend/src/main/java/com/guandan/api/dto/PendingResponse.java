package com.guandan.api.dto;

/** 202 登记回执：命令已登记，不表示真人动作已提交。 */
public record PendingResponse(String requestId, String status, long pollAfterMs) {
    public PendingResponse(String requestId, long pollAfterMs) {
        this(requestId, "PENDING", pollAfterMs);
    }
}