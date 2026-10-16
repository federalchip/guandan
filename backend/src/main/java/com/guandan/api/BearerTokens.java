package com.guandan.api;

/** 从 {@code Authorization: Bearer <token>} 头提取会话令牌；缺失、前缀不符或空令牌均返回 null。 */
final class BearerTokens {

    private static final String PREFIX = "Bearer ";

    private BearerTokens() {
    }

    static String token(String authHeader) {
        if (authHeader != null && authHeader.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            String token = authHeader.substring(PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
