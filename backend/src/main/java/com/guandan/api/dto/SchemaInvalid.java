package com.guandan.api.dto;

import com.guandan.domain.model.GameRuleException;

/** 构造 SCHEMA_INVALID(422) 的小工具，供 DTO 边界校验复用。 */
final class SchemaInvalid {

    private SchemaInvalid() {
    }

    static GameRuleException of(String message) {
        return new GameRuleException("SCHEMA_INVALID", message);
    }
}
