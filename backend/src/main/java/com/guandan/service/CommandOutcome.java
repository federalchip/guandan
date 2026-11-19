package com.guandan.service;

import com.guandan.api.dto.CommandResponse;

/** 命令提交与状态查询的统一结果：完成（原 CommandResponse）、处理中、终态失败。 */
public sealed interface CommandOutcome {
    record Completed(CommandResponse response) implements CommandOutcome {}
    record Pending(String requestId, long pollAfterMillis) implements CommandOutcome {}
    record Failed(String code, String message) implements CommandOutcome {}
}