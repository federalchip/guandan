package com.guandan.api.dto;

import java.util.Map;

/** 统一错误响应体：{code, message, details}；不泄露 traceback、seed 或他人手牌。 */
public record ErrorBody(String code, String message, Map<String, Object> details) {
}
