package com.guandan.api.dto;

import java.util.UUID;

/** 命令请求：request_id(UUID)、expected_version(必填非负整数)、action(判别联合)。 */
public record CommandRequest(String requestId, Long expectedVersion, ActionRequest action) {

    public void validate() {
        if (requestId == null || requestId.isBlank()) {
            throw SchemaInvalid.of("request_id 必填");
        }
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            throw SchemaInvalid.of("request_id 必须为 UUID 字符串: " + requestId);
        }
        if (expectedVersion == null) {
            throw SchemaInvalid.of("expected_version 必填");
        }
        if (expectedVersion < 0) {
            throw SchemaInvalid.of("expected_version 必须为非负整数");
        }
        if (action == null) {
            throw SchemaInvalid.of("action 必填");
        }
        action.validate();
    }
}
