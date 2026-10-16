package com.guandan.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验 springdoc 生成的 OpenAPI 覆盖既定接口面（路径、snake_case 字段、无 presence 泄漏），并可按需把
 * 规范导出到 docs/openapi.json 供前端 openapi-typescript 生成 TS 类型。导出仅在 {@code -Dopenapi.export=true}
 * 时写文件，避免默认测试改动被跟踪文件；另有 drift 检查在文件存在时断言实时规范与已提交一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTest {

    private static final Path SPEC = Path.of("..", "docs", "openapi.json");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    private JsonNode liveSpec() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    @Test
    void spec_covers_game_routes_and_snake_case_action_fields() throws Exception {
        JsonNode spec = liveSpec();
        assertTrue(spec.get("openapi").asText().startsWith("3."), "应为 OpenAPI 3.x");
        JsonNode paths = spec.get("paths");
        for (String p : new String[]{"/api/v1/games", "/api/v1/games/{id}",
                "/api/v1/games/{id}/legal-actions", "/api/v1/games/{id}/resolve",
                "/api/v1/games/{id}/actions"}) {
            assertTrue(paths.has(p), "缺少路径 " + p);
        }
        JsonNode action = spec.at("/components/schemas/ActionRequest/properties");
        assertTrue(action.has("kind"), "ActionRequest.kind 缺失");
        assertTrue(action.has("card_ids"), "应为 snake_case card_ids");
        assertTrue(action.has("interpretation_key"), "应为 snake_case interpretation_key");
        assertFalse(action.has("cardIdsPresent"), "presence 元数据不得进入规范");
        assertFalse(action.has("card_ids_present"), "presence 元数据不得进入规范");
        assertFalse(action.has("interpretationKeyPresent"), "presence 元数据不得进入规范");
    }

    @Test
    void committed_spec_matches_live_when_present() throws Exception {
        if (!Files.exists(SPEC)) {
            return;   // 首次生成前跳过
        }
        JsonNode committed = om.readTree(Files.readString(SPEC));
        assertEquals(committed, liveSpec(),
                "docs/openapi.json 与实时规范不一致，请用 -Dopenapi.export=true 重新生成");
    }

    @Test
    void export_spec_when_requested() throws Exception {
        if (!"true".equals(System.getProperty("openapi.export"))) {
            return;
        }
        JsonNode spec = liveSpec();
        Files.createDirectories(SPEC.toAbsolutePath().getParent());
        Files.writeString(SPEC, om.writerWithDefaultPrettyPrinter().writeValueAsString(spec)
                + System.lineSeparator());
    }
}
