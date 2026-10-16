package com.guandan.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档元信息。规范由 springdoc 从控制器与 {@code api/dto} 自动派生，导出为 docs/openapi.json
 * 供前端生成 TS 类型；判别联合、错误码等语义以 docs/api-v1.md 为权威。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI guandanOpenApi() {
        return new OpenAPI().info(new Info()
                .title("掼蛋 v1 API")
                .version("v1")
                .description("单局 · 两副牌 108 张 · 固定打 2；语义权威见 docs/api-v1.md"));
    }

    /**
     * 让 springdoc 的 schema 解析复用应用的 ObjectMapper（含 SNAKE_CASE 命名策略），
     * 否则生成的字段名为 camelCase，与实际 HTTP JSON（snake_case）不符。
     */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }
}
