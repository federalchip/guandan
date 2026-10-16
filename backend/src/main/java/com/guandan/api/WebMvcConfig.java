package com.guandan.api;

import com.guandan.service.GameService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册前置会话鉴权拦截器。仅拦截已定位到具体牌局的路由
 * （{@code /api/v1/games/{id}} 与 {@code /api/v1/games/{id}/**}），放行创建 {@code POST /api/v1/games}
 * 与 {@code /api/v1/health}（二者无需会话）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final GameService service;

    public WebMvcConfig(GameService service) {
        this.service = service;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionAuthInterceptor(service))
                .addPathPatterns("/api/v1/games/*", "/api/v1/games/*/**");
    }
}
