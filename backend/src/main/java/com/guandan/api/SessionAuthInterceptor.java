package com.guandan.api;

import com.guandan.service.GameService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 前置会话鉴权。{@code preHandle} 在 {@code @RequestBody} 反序列化之前运行，对已定位到具体牌局的
 * 路由（{@code /api/v1/games/{id}} 及其子路由）先校验会话令牌：缺令牌→401、局不存在→404、他局
 * 令牌→403。于是“缺少令牌先 401”的错误优先级（计划 line278）对 JSON 语法/类型等解析期错误同样成立，
 * 而不再由 Spring 抢先返回 422。创建（{@code POST /api/v1/games}）与 {@code /health} 不在拦截范围。
 * 抛出的 {@link com.guandan.domain.model.GameRuleException} 由 {@link ApiExceptionHandler} 映射状态码。
 */
public class SessionAuthInterceptor implements HandlerInterceptor {

    private final GameService service;

    public SessionAuthInterceptor(GameService service) {
        this.service = service;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Map<String, String> vars = uriTemplateVars(request);
        String gameId = vars == null ? null : vars.get("id");
        service.requireSession(gameId, BearerTokens.token(request.getHeader("Authorization")));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> uriTemplateVars(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return attr instanceof Map ? (Map<String, String>) attr : null;
    }
}
