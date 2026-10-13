package com.guandan.domain.model;

import java.util.Map;

/**
 * 非法动作时抛出；抛出前输入状态不发生修改。code 为稳定错误码，details 为公开附加信息。
 */
public class GameRuleException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public GameRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    public GameRuleException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
