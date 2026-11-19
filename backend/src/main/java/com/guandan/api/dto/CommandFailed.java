package com.guandan.api.dto;

import java.util.Map;

/** 终态失败：已整批回滚，同 ID 幂等缓存，不再执行。 */
public record CommandFailed(String status, ErrorBody error) {
    public CommandFailed(String code, String message) {
        this("FAILED", new ErrorBody(code, message, Map.of()));
    }
}